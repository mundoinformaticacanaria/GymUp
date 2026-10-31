# #56 — Inventario estático de escritores y lectores DAO

Fecha: 2026-10-29 · Alcance: rama `fix/issue-56-backup-rollback` / PR #57.

## Verificación por interfaz y adaptador

Comparados los métodos declarados en los contratos de repositorio con los
`override` de sus adaptadores de barrera, y contrastado su cableado en
`DefaultAppContainer`:

| Interfaz expuesta | Métodos mutadores bajo `withDomainWrite` | Métodos delegados de solo lectura |
| --- | ---: | --- |
| `SessionRepository` | 19/19 | `observeSessions`, `observeSessionsForDate`, `getSessionDetail` |
| `RoutineRepository` | 2/2 | `observeRoutines`, `getRoutineDetail` |
| `CatalogMaintenanceRepository` | 6/6 | `observeMasters`, `getExercise`, `previewExerciseDeletion` |
| `ExerciseCatalogRepository` | 1/1 (`setFavorite`) | `observeActiveExercises`, `findByNormalizedName` |

Los cuatro adaptadores se instancian en `DefaultAppContainer` con el mismo
`BackupDomainWriteBarrier`. El catálogo maestro y el historial son rutas de
lectura en `RoomMasterCatalogRepository` y `RoomHistoryRepository`.
`ExerciseImageManager` recibe esa barrera y la aplica a sus operaciones
mutadoras; `UserPreferencesRepository.setThemeMode` también la adquiere.
`HistoricalCleanupManager` recibe `BarrierProtectedHistoricalCleanupStore`
para borrado y compactación. El seeding y la recuperación al arranque se
ejecutan desde `BackupStartupGate` dentro de la sección exclusiva.
El delegado crudo de backup/preferencias se usa exclusivamente dentro de la
exclusión externa, para evitar un mutex no reentrante.

## Inspección de rutas concretas

- `RoomSessionRepository`: escrituras de sesiones, ejercicios y series
  mediante transacciones Room, expuestas por el adaptador protegido.
- `RoomRoutineRepository`: altas/cambios y borrados de rutinas expuestos
  por el adaptador protegido.
- `RoomCatalogMaintenanceRepository`: mutaciones de maestros y ejercicios,
  incluidas imágenes USER, expuestas por el adaptador protegido.
- `RoomHistoryRepository`: consultas y transacción de lectura histórica;
  no se observó escritura en las rutas revisadas.
- `AndroidSessionReportFiles`: escribe informes en caché para compartir;
  no altera Room, imágenes USER persistentes ni preferencias.

## Limitaciones y gates pendientes

Este inventario acredita la **cobertura de los métodos públicos revisados**,
no una prueba de ausencia de accesos DAO directos en todos los call sites,
reflexión, otros procesos o cambios futuros. Los métodos de lectura/Flow se
delegan sin lock: el gate de Compose y sus leases deben verificarse en dos
Activity y durante rotación real. Una barrera de escritores no convierte
Room, DataStore y el filesystem en una transacción atómica, ni acredita
durabilidad frente a muerte de proceso o corte eléctrico.

**No cerrar #56 ni fusionar PR #57** sin revisión final de llamadas directas,
ejecución instrumentada Android 15/16, durabilidad y regresión física BF-02.

## Instrumentación de referencias USER compartidas — 2026-10-29 15:10 UTC

Se añade a `UserImageOrphanReconcilerDeviceTest` una prueba Android con
almacenamiento `filesDir` aislado y Room **en memoria**: dos filas USER
referencian la misma `storage_key` (la columna no tiene unicidad global),
mientras un archivo UUID independiente carece de referencias. El reconciliador
debe conservar íntegro el archivo compartido y las dos filas, eliminar el
huérfano y sincronizar el directorio real. No toca los datos instalados.

La prueba está **publicada, no ejecutada en dispositivo**. El CI ordinario
solo compila `androidTest`; un resultado verde no demuestra compatibilidad
física ni durabilidad tras muerte de proceso. Quedan pendientes ejecución
Android 15/16, rotación/multi-Activity, revisión completa de lectores y
pruebas de interrupción de Room/DataStore/filesystem. #56 sigue abierta y
PR #57 en borrador.

## Rotación durante cierre de lectores — 2026-10-29 18:10 UTC

Se añade una prueba `androidTest` de dos `MainActivity` reales para la
carrera de recreación: con la segunda Activity detenida pero aún compuesta,
se inicia una importación con ZIP deliberadamente inválido y se recrea la
primera Activity mientras el coordinador permanece en `ClosingReaders`.
Un acuse adicional no debe liberar el lease de la Activity detenida;
solo al reanudarla y desmontar su árbol puede completarse la validación del
archivo inválido. La prueba evita sustituir Room, preferencias o imágenes.

**Cobertura pendiente de ejecución:** el CI ordinario compila `androidTest`
pero no lo ejecuta en un dispositivo. La rotación y la concurrencia deben
probarse físicamente en Android 15/16; siguen pendientes la auditoría global
de lectores/DAO, durabilidad Room/DataStore/directorios y cortes de proceso.
#56 permanece abierta y PR #57 en borrador.

## Regresión de recuperación tras cierre de dos Activity — 2026-10-29 19:09 UTC

El test JVM de `HotBackupImportCoordinatorTest` añadido en `4f22890` verifica
que una importación que fallará y necesitará recuperación no puede empezar
mientras una segunda Activity conserva su lease de lectura. Un acuse adicional
de la primera Activity no permite iniciar ni la importación ni el callback
de recuperación; solo al liberar la última lease se ejecutan ambas fases.
Tras la recuperación, una nueva generación puede adquirir lectores.

Esta es una prueba determinista de coordinación en memoria, **no** una
ejecución de dos Activity Android reales, un corte de proceso o una prueba
de durabilidad. Su CI debe verificarse por separado; se mantienen abiertas
las puertas de Android 15/16, accesos DAO externos y recuperación física.

## Reintento de PREPARED en Android real — 2026-10-29 20:12 UTC

El test instrumentado `BackupRecoveryJournalDeviceTest.failedReplayKeepsPreparedJournalForDeviceRetry`
(commit `08bebd3`) prepara una preimagen en almacenamiento privado, inyecta un
fallo durante su restauración, reconstruye el coordinador y exige que el
journal `PREPARED` siga íntegro. El segundo intento debe restaurar una sola
vez, confirmar y limpiar; un tercer arranque no puede repetir la restauración.

**Estado:** test publicado, pendiente de compilación en CI y de ejecución
`connectedDebugAndroidTest` sobre Android 15/16. No mata realmente el
proceso ni simula pérdida de energía: comprueba recuperación tras fallo
inyectado y reconstrucción de objetos con el filesystem del dispositivo.
No cierra las puertas de durabilidad Room/DataStore/directorios, auditoría
de accesos DAO ni regresión física BF-02. #56 y PR #57 siguen abiertas.

## Hallazgo de residuos de importación fuera de la raíz activa — 2026-10-30

Inspección estática de `RoomBackupDataSource.kt`, `BackupStartupGate.kt` y
`UserImageOrphanReconciler.kt` en PR #57 (HEAD previo `1363029`):

- `UserExerciseImageStore.stageReplacement` crea directorios hermanos
  `exercise-images-import-<UUID>`; `StagedImageReplacement` aparta la raíz
  anterior como `exercise-images-previous-<UUID>`.
- `StagedImageReplacement.complete()` ignora el `false` de
  `deleteFlatImageDirectoryNoFollow(previous)` y marca `finished = true`.
  Una caída entre renames/limpieza también puede dejar estos directorios.
- La reconciliación actual de arranque examina **solo**
  `filesDir/exercise-images` y sus ficheros UUID huérfanos. No recorre los
  hermanos `-import-`/`-previous-`; `BackupRecoveryJournal` retira sus
  propios artefactos, no los de `StagedImageReplacement`.

**Consecuencia potencial, no observada en dispositivo:** copias antiguas de
imágenes USER o staging podrían quedar retenidas sin limpieza posterior,
consumiendo espacio y conservando datos que el usuario cree sustituidos.
Esto no demuestra pérdida de referencias Room ni autoriza borrar los residuos
sin más: un directorio `-previous-` puede ser la última copia recuperable
si el journal o la raíz activa están dañados.

**Trabajo técnico pendiente en #56:** diseñar y probar reconciliación de
residuos después de resolver `PREPARED` y confirmar la raíz/refs autoritativas;
limitarla a nombres generados con UUID, sin seguir symlinks ni borrar rutas
inesperadas; preservar datos ante raíz ausente/corrupta; sincronizar metadatos
del padre; cubrir caída/limpieza parcial/reintento y comprobar API 35/36.
No se ha aplicado una limpieza automática en esta revisión para evitar
eliminaciones irreversibles sin estas garantías. La PR permanece DRAFT.

## Preparación conservadora de limpieza de staging — 2026-10-30 01:12 UTC

Los commits `bb29692` y `5efe425` incorporan **una primitiva aislada, no conectada al arranque**: `AbandonedImageImportStagingCleaner`. Se limita a directorios `exercise-images-import-<UUID>` generados por el importador. No borra nunca `exercise-images-previous-<UUID>`, que podría contener la última copia recuperable. Solo considera un staging para retirada si la raíz activa es un directorio plano seguro, todas las referencias USER suministradas están presentes como ficheros regulares no vacíos y el staging contiene únicamente imágenes UUID regulares; rechaza enlaces y subdirectorios sin seguirlos. Sin raíz activa o con referencias ausentes, conserva íntegro el staging. Sincroniza el padre tras cada retirada y permite reintentar. Las pruebas JVM cubren limpieza selectiva, idempotencia, raíz ausente/enlazada, referencia ausente, staging enlazado y contenido anidado.

**No confundir esta implementación de laboratorio con una corrección de producción.** El método requiere que el llamador haya resuelto el journal y mantenga la barrera exclusiva; no se ha cableado en `DefaultAppContainer` ni `BackupStartupGate`. La validación estructural no acredita integridad de bytes, durabilidad física, ausencia de carreras TOCTOU ni exclusión entre procesos. El borrado de `-previous-` permanece expresamente prohibido hasta demostrar qué raíz es autoritativa incluso tras corte real. Las pruebas nuevas deben superar CI; siguen pendientes Android 15/16, cortes y auditoría final. PR #57 permanece DRAFT.

## Regresiones adicionales de limpieza fail-closed — 2026-10-30 02:10 UTC

Se añaden tres pruebas JVM en `AbandonedImageImportStagingCleanerTest` (commit `07780d3`): (1) un archivo inesperado en la raíz activa impide retirar staging; (2) una `storage_key` USER malformada impide cualquier borrado; (3) un fallo inyectado de `syncParent` se propaga y un segundo intento resulta idempotente, sin alterar la imagen activa. Estas pruebas verifican únicamente el comportamiento controlado del limpiador aislado. **No demuestran durabilidad física tras fsync fallido** ni habilitan su conexión al arranque. El nuevo HEAD debe superar CI; permanecen pendientes Android 15/16, muerte real de proceso, recuperación segura de `-previous-`, revisión DAO y BF-02 física.

## Regresión mixta de staging y enlace simbólico — 2026-10-30 03:07 UTC

El commit `7de7120` añade una prueba JVM para dos directorios de staging simultáneos: uno plano y seguro y otro con un fichero UUID que es un enlace simbólico hacia un archivo externo. La limpieza aislada debe retirar únicamente el primero, conservar el segundo y su enlace, no modificar los bytes externos, preservar `exercise-images-previous-*` y permitir un segundo intento idempotente. Esta cobertura amplía la prevalidación de selectividad; **no demuestra ausencia de carreras TOCTOU, integridad de bytes ni durabilidad ante cortes**. La primitiva sigue sin conectarse a producción y su CI debe validarse para el HEAD exacto.

## Límites de tamaño en limpieza conservadora — 2026-10-30 04:15 UTC

La prueba JVM añadida en `6966cd0` verifica dos precondiciones que evitan borrar copias potencialmente recuperables: (1) con staging seguro y otros dos candidatos, los que contienen un fichero de cero bytes o uno mayor de 10 MiB se preservan mientras el seguro se elimina; (2) si una imagen USER referenciada en la raíz activa está vacía o excede 10 MiB, no se retira ningún staging hasta reparar esa imagen. Los casos ejercitan el límite estricto y la reevaluación en el reintento, no prueban la integridad del contenido.

**Estado al publicar:** pruebas añadidas, pendiente confirmar CI para el nuevo HEAD. El limpiador sigue aislado, sin uso en producción. No se ha demostrado ausencia de TOCTOU, durabilidad tras corte, recuperación de `-previous-`, ejecución API 35/36 ni regresión física BF-02; la PR debe permanecer DRAFT.

## Desajuste de formato USER entre backup y limpieza — 2026-10-30 05:15 UTC

La validación vigente de `BackupDataCodec.validate` y `UserExerciseImageStore.stageReplacement` acepta claves USER que cumplan `^user:[0-9a-fA-F-]{36}$`, incluso con letras mayúsculas o guiones no canónicos. En cambio, `AbandonedImageImportStagingCleaner` solo considera seguros nombres UUID canónicos en minúsculas (`8-4-4-4-12`). La diferencia significa que un staging generado a partir de un backup formalmente aceptado **puede permanecer retenido indefinidamente** por la limpieza conservadora; si la raíz activa contiene un fichero no canónico, la limpieza de cualquier staging queda bloqueada.

Dos pruebas JVM en el commit `9dad43d` fijan el comportamiento de **preservación** (no borrado): staging no canónico junto a staging seguro y raíz activa con referencia USER no canónica. No se cambia el validador de backups ni se amplía el borrado: antes de hacerlo hay que decidir y probar una normalización/compatibilidad que no elimine datos recuperables. El hallazgo es estático y las pruebas no acreditan durabilidad Android ni ausencia de carreras. **CI del nuevo HEAD pendiente de verificar** al redactar esta nota. Limpiador todavía desconectado de producción; PR #57 DRAFT.

## Compatibilidad de nombres USER de ZIP v1 — 2026-10-30 06:10 UTC

La CI [#41007565955](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41007565955) terminó **SUCCESS** para el HEAD anterior `a0b27de`, validando las dos regresiones de preservación fail-closed. El desajuste descrito arriba se aborda en `bd6f56a`: el limpiador aislado usa exactamente el conjunto de caracteres de nombres USER aceptado por `BackupDataCodec.validate` y `UserExerciseImageStore.stageReplacement` (`[0-9a-fA-F-]{36}`), conservando el prefijo `user:`. No normaliza ni renombra ficheros existentes: la comparación es exacta, con distinción de mayúsculas/minúsculas, y no permite separadores de ruta, puntos, barras inversas ni caracteres de control.

La prueba `3a5d969` sustituye las expectativas históricas de retención por limpieza compatible para nombres con hexadecimales mayúsculos, y añade un caso de 36 caracteres hex/guion no canónico admitido por ZIP v1. Los casos existentes de referencia malformada, symlink, archivo extraño, tamaño inválido y directorio `-previous-` conservado siguen como límites de seguridad. **La nueva CI debe validar estos cambios**; no atribuirles el SUCCESS del HEAD anterior.

Esta compatibilidad solo resuelve la discrepancia de formato en el limpiador **no conectado a producción**. No constituye autorización para ejecutar limpieza automática: faltan pruebas instrumentadas API 35/36, durabilidad ante corte, auditoría de lectores/DAO y demostración de que la raíz activa y el journal permiten descartar staging sin perder la última copia recuperable. `exercise-images-previous-*` continúa intocable. PR #57 DRAFT y #56 abierta.

## Revisión de CI y protección de imágenes activas — 2026-10-30 07:12 UTC

La [Android CI #41012586650](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41012586650) finalizó **SUCCESS** para el HEAD exacto `9b08677` (job `verify`: tests JVM/Robolectric, lint, compilación debug y compilación de fuentes `androidTest`). No equivale a ejecutar pruebas instrumentadas ni acredita durabilidad frente a cortes.

La inspección del `AndroidManifest.xml` de esta rama muestra una sola `MainActivity` y un `FileProvider` no exportado, sin atributo `android:process` explícito en esos componentes; `android:allowBackup="false"` sigue vigente. Esto solo caracteriza el manifiesto fuente, **no** demuestra ausencia de componentes añadidos por el manifest merger ni garantiza exclusión de otros procesos o lectores externos.

El commit `70f70e4` añade una regresión JVM de una **imagen USER activa que es symlink** a un archivo externo: la limpieza aislada debe conservar staging y bytes externos, sin seguir el enlace. El commit `76b0a77` corrige saltos de línea literales en el KDoc del limpiador, sin alterar su lógica. **Estos commits nuevos requieren CI del HEAD exacto** antes de declararse validados.

El limpiador sigue desconectado de producción; `exercise-images-previous-*` permanece intocable. Continúan abiertos los gates de integridad de bytes, carreras TOCTOU, manifest fusionado/procesos, auditoría de lectores fuera de Compose, Android API 35/36 y cortes reales de Room/DataStore/filesystem, así como la regresión BF-02.

## CI del HEAD previo y dos regresiones de enlaces — 2026-10-30 08:13 UTC

La [CI #41018696669](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41018696669) terminó **SUCCESS** para el HEAD exacto `e3d4794`, incluido el job `verify` (tests JVM/Robolectric, lint, build debug y compilación de fuentes androidTest). Esto valida la regresión de symlink de imagen USER activa publicada en `70f70e4`; **no ejecuta androidTest en dispositivo**.

El commit `15fa283` añade dos regresiones JVM adicionales para la primitiva de limpieza **aislada**: (1) si el propio `filesDir` recibido es un enlace simbólico, la operación falla antes de inspeccionar/borrar staging; (2) un `exercise-images-previous-*` enlazado a una carpeta externa nunca se recorre ni se elimina, aunque se retire un staging seguro. Los tests no implican conexión a producción y **requieren CI del nuevo HEAD** antes de declararse validados.

Quedan abiertos los mismos gates: AVD desechable Android 15/16, prueba de muerte real de proceso y durabilidad, revisión de lectores/DAO y manifiesto fusionado, recuperación segura de `-previous-*`, carreras TOCTOU y regresión física BF-02. No fusionar PR #57 ni cerrar #56 por estas pruebas.

## Comprobación de referencias USER al arrancar — 2026-10-30 09:14 UTC

Revisión estática de `UserImageOrphanReconciler.reconcile()`,
`BackupStartupGate.awaitReady()`, `DefaultAppContainer`,
`ExerciseImageManager.safeReadableUserImageFile()` y
`app/src/main/AndroidManifest.xml` en PR #57:

- Si `filesDir/exercise-images` no existe, el reconciliador retorna antes
  de consultar `getAllUserImageStorageKeys()`. Por tanto, una base Room con
  referencias `user:` y raíz de imágenes ausente puede superar el gate de
  arranque sin que se compruebe la integridad referencial de esas imágenes.
- Si la raíz existe, el reconciliador enumera huérfanos y conserva las
  referencias conocidas, pero tampoco comprueba que **cada referencia**
  corresponda a un archivo regular presente, legible y no vacío. El lector
  `ExerciseImageManager` rechaza rutas inseguras y puede devolver `null`,
  lo que evita seguir enlaces pero no detecta de forma global la inconsistencia.
- El manifiesto **fuente** declara una `MainActivity` y un `FileProvider`,
  sin atributos `android:process`; esto no sustituye inspeccionar el
  manifiesto **fusionado** de la variante ni descarta otros procesos externos.

**Alcance del hallazgo:** es una laguna de validación por lectura del código,
no una pérdida de datos observada ni evidencia de que la recuperación del
journal falle. La ausencia puede provenir de corrupción previa o de una
interrupción fuera de las ventanas ya cubiertas. No se modifica el arranque:
una política de bloqueo por imágenes ausentes podría impedir acceder a
sesiones históricas y requiere valorar el comportamiento antes de adoptarla.

**Siguiente trabajo técnico:** crear fixtures aisladas con Room que contenga
referencias USER y raíz ausente, imagen ausente y enlace simbólico; comprobar
el comportamiento real del gate y definir una comprobación fail-closed o
recuperación que no destruya las últimas copias. Validar en Android 15/16
junto con journal PREPARED/COMMITTED y `-previous-*`, antes de habilitar
limpieza de staging o cerrar #56. La CI del HEAD anterior `9872a3f`
([run 41025008009](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41025008009))
terminó SUCCESS con JVM/Robolectric, lint, debug y compilación androidTest,
0 artefactos; **no ejecutó pruebas instrumentadas**.

## Diagnóstico aislado de referencias USER — 2026-10-30 10:14 UTC

Se incorpora `UserImageReferenceIntegrityInspector` como **diagnóstico de solo lectura, no conectado al arranque**. A diferencia de `UserImageOrphanReconciler`, recorre las claves USER de Room incluso cuando no existe `exercise-images` y devuelve las referencias con archivo ausente, no regular, enlazado, vacío, excesivo o nombre no admitido por ZIP v1. Abre el archivo final con `NOFOLLOW_LINKS` para consultar tamaño; no borra, restaura, modifica Room ni bloquea la UI. La raíz enlazada o no-directorio provoca error en lugar de seguir rutas externas.

Tres pruebas Robolectric con Room en memoria y directorio privado aislado cubren raíz ausente con referencia, mezcla de imagen válida en mayúsculas / ausente / vacía / enlazada y ausencia legítima de raíz sin referencias. **No se afirma que el arranque ya detecte estos casos**: el inspector sigue deliberadamente desconectado hasta resolver el tratamiento de imágenes históricas ausentes, el journal y las preimágenes `-previous-*`. No comprueba hash/contenido de imagen ni excluye carreras TOCTOU, otros procesos o cortes reales; necesita barrera y recuperación previa para uso futuro. CI del HEAD nuevo pendiente de verificación; Android 15/16 y regresión BF-02 siguen pendientes.

## Inspección USER: ruta privada enlazada y referencias corruptas — 2026-10-30 11:14 UTC

La nueva primitiva `UserImageReferenceIntegrityInspector` de solo lectura comprobaba
la raíz final `exercise-images` con `NOFOLLOW_LINKS`, pero no rechazaba un
`filesDir` padre que fuese un enlace simbólico. En ese caso la lectura podía
atravesar el enlace padre aunque el último componente fuese regular.
El commit `aa249bd` exige ahora que `filesDir` sea un directorio real
(`NOFOLLOW_LINKS`) antes de inspeccionar las referencias; falla cerrado sin
reparar ni eliminar nada.

El commit `d7e9b18` incorpora dos regresiones Robolectric con Room en memoria:
padre privado enlazado a un árbol externo, preservando sus bytes, y mezcla de
referencia USER válida, archivo superior a 10 MiB y clave de ruta malformada.
La inspección debe informar únicamente las referencias inseguras y conservar
filas y archivos. **Pendiente confirmar CI del HEAD exacto**; no son pruebas
instrumentadas ni prueban ausencia de TOCTOU en componentes ancestros, otros
procesos, durabilidad de journal o recuperación tras corte real.

El inspector continúa **desconectado de producción** y no define política de
bloqueo para imágenes históricas ausentes. `exercise-images-previous-*`
permanece preservado; PR #57 sigue DRAFT hasta Android 15/16, durabilidad,
auditoría de lectores/DAO y regresión BF-02.

## Comprobación de raíz USER no válida — 2026-10-30 12:07 UTC

La [CI #41046655556](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41046655556)
finalizó **SUCCESS** para el HEAD anterior `8e6153c`: job `verify`
(JVM/Robolectric, lint, debug y compilación androidTest), sin artefactos
publicados ni ejecución instrumentada en Android 15/16.

Se añaden dos regresiones Robolectric en `UserImageReferenceIntegrityInspectorTest`
(commit `a43c680`): raíz activa `exercise-images` enlazada a otro directorio
con bytes externos, y ruta de raíz ocupada por un archivo ordinario. En ambos
casos el inspector aislado debe fallar cerrado sin alterar referencias Room
ni archivos externos. Estas pruebas complementan la guardia previa sobre
`filesDir`, **sin cambiar código productivo**.

La CI del nuevo HEAD requiere confirmación. El inspector y el limpiador
siguen desconectados de producción; no se borran `-previous-*`.
No se ha demostrado durabilidad tras corte, ausencia de TOCTOU ni
ejecución física de instrumentación API 35/36. La PR #57 permanece DRAFT.

## Lectores observables y referencias compartidas — 2026-10-30 13:14 UTC

Revisión estática adicional de `DefaultAppContainer`, `GymUpApp`,
`RoomHistoryRepository`, `RoomCatalogRepositories`,
`AppViewModel` y `UserPreferencesRepository`:

- `RoomHistoryRepository` consulta directamente `trainingDao`,
  `routineDao` y `exerciseDao`, incluyendo dos `Flow` observables y
  `getExerciseHistory` con transacción de lectura. No utiliza la barrera
  de escritores; no se observó mutación en ese adaptador.
- `RoomMasterCatalogRepository` expone tres `Flow` de lectura de DAO
  sin adquirir la barrera. Esto es coherente con el diseño actual solo
  mientras **todos** los lectores UI permanezcan bajo leases y sean
  desmontados antes del replace-all.
- `GymUpReadyApp` adquiere el lease mediante `registerReader()` antes
  de construir el `AppViewModel` y el `NavHost`; su
  `ReaderViewModelOwner` limpia el `ViewModelStore` antes de liberar el
  lease. El `AppViewModel` colecciona DataStore desde ese store. La
  inspección de código no demuestra el comportamiento real con dos
  Activity, recreación, procesos externos ni lectores futuros.

Se publican dos pruebas Robolectric **solo del inspector aislado** en
`UserImageReferenceIntegrityInspectorTest` (commit `1eb3e4d`):
(1) dos filas Room que comparten la misma clave USER ausente deben
informarse como una sola clave insegura, conservando ambas referencias;
(2) el límite de 10 MiB es inclusivo y un byte adicional se informa
como inseguro, sin alterar archivos ni filas. **Pendiente CI del HEAD
nuevo**; no son pruebas Android instrumentadas ni acreditan durabilidad,
integridad de contenido o seguridad ante cortes.

El inspector y el limpiador siguen desconectados de producción. La
auditoría de call sites y procesos no está cerrada; no activar limpieza
automática ni fusionar PR #57 hasta cumplir API 35/36, cortes reales,
manifiesto fusionado y regresión física BF-02.

## Reconciliación de huérfanos: directorio privado padre — 2026-10-30 14:15 UTC

La auditoría comparada de `UserImageReferenceIntegrityInspector` y
`UserImageOrphanReconciler` detectó que el reconciliador, **conectado al
arranque**, rechazaba symlinks en `exercise-images` y en los ficheros
huérfanos, pero no en su `filesDir` padre. Un padre sustituido por enlace
podía dirigir el borrado de un huérfano con nombre UUID canónico a un árbol
externo. Es un riesgo estático; **no consta pérdida de datos real**.

En PR #57, `c01a19c` exige que el padre sea un directorio real mediante
`Files.isDirectory(..., NOFOLLOW_LINKS)` **antes** de enumerar o eliminar
huérfanos. `1ebde61` añade una regresión Robolectric aislada con un padre
simbólico, archivo externo de nombre canónico y comprobación de que sus bytes
se preservan. La prueba no demuestra ausencia de carreras TOCTOU ni protección
de todos los ancestros, y debe validarse por CI del HEAD correspondiente.

No se conecta el inspector ni el limpiador de staging, no se eliminan
`exercise-images-previous-*` y no se cambia el contrato funcional.
Permanecen las puertas de Android 15/16, durabilidad ante cortes,
auditoría completa de lectores/procesos/manifiesto fusionado y BF-02 física.

## Guardia automatizada del proceso en manifiesto debug — 2026-10-30 16:10 UTC

La [Android CI #41078963399](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41078963399) terminó **SUCCESS** para `f9ed9ef`, incluida la regresión de nombres USER heredados. Solo ejecutó JVM/Robolectric, lint y compilación; **no** Android 15/16 instrumentado ni pruebas de durabilidad.

Se añade `MergedManifestProcessIsolationTest` en `app/src/test/.../app/` (commits `b17ace8` y `5882846`). Consulta el `PackageManager` de Robolectric para la variante **debug** y comprueba que la aplicación, Activity, servicios, receptores y proveedores declarados compartan el proceso por defecto. Exige encontrar `MainActivity` y `FileProvider` para evitar un éxito vacuo sin componentes. Su propósito es detectar regresiones de `android:process` introducidas por el manifiesto propio o dependencias, ya que el journal y la barrera de escritores solo protegen el proceso local.

**Límites:** el test nuevo necesita CI del HEAD exacto; una ejecución JVM no demuestra que el manifiesto fusionado de release coincida ni que Android 15/16 no exponga procesos externos, lectores vivos o carreras. Es un guardarraíl estático de debug, no evidencia de aislamiento real, corte de proceso, fsync durable o recuperación de `exercise-images-previous-*`. Mantener #56 abierta, PR #57 DRAFT, inspector y limpiador de staging desconectados, y no fusionar ni publicar APK sin las puertas pendientes.

## Baja de ejercicio: padre privado enlazado — 2026-10-30 17:15 UTC

La revisión de `RoomCatalogMaintenanceRepository.deleteExercise()` encontró una
asimetría respecto de `UserImageOrphanReconciler`: el primero validaba la
raíz `exercise-images` y el archivo final, pero no el propio `filesDir`.
Si el padre privado se sustituía por un symlink a otro árbol, la baja podía
confirmar Room y después intentar eliminar una imagen fuera de ese árbol
privado. Es un riesgo deducido del código, **no una pérdida observada**.

En `ab63cc0` se exige `Files.isDirectory(filesDir, NOFOLLOW_LINKS)` antes
de confirmar la transacción Room y antes de cada eliminación, con rechazo
fail-closed. En `dfc82b6` se añade una regresión Robolectric con
`ContextWrapper` que entrega un padre privado enlazado, y comprueba
conservación de la fila, su referencia y los bytes externos. **CI del nuevo
HEAD pendiente de comprobación**; no atribuirle el éxito anterior.

La corrección no convierte la baja Room+filesystem en atómica ni elimina
TOCTOU por sustitución posterior del padre/raíz, fallo de fsync o corte real.
Siguen pendientes validación instrumentada Android 15/16, recuperación
física, auditoría restante y regresión BF-02. No activar el limpiador aislado
ni fusionar PR #57 por esta sola protección.

## Exportación y staging de backup: padre privado enlazado — 2026-10-30 18:09 UTC

La inspección de `UserExerciseImageStore.readRequired()` y `stageReplacement()`
en `RoomBackupDataSource.kt` detectó que ambos validaban la raíz
`exercise-images` o el archivo final, pero **no** comprobaban que
`context.filesDir` fuese un directorio real sin enlaces. Un `filesDir`
sustituido por un symlink podía permitir que la exportación leyera bytes
fuera de la raíz privada prevista, o que la importación creara staging en
un árbol externo. Es un riesgo estático, **sin incidente real confirmado**.

El commit `c2bd3ce` añade una precondición `NOFOLLOW_LINKS` al padre
antes de leer imágenes requeridas y antes de crear staging; `919127d`
incorpora dos pruebas Robolectric con `ContextWrapper` que simulan el
padre enlazado y verifican rechazo, conservación de referencias Room,
preferencias y bytes externos. La prueba de importación exige además que
no se cree staging en el destino enlazado. **La CI del HEAD nuevo debe
confirmarse** antes de declarar estas pruebas superadas.

La comprobación de preflight **no** elimina carreras TOCTOU de sustitución
posterior del padre, ni demuestra aislamiento por descriptores de todos
los ancestros, durabilidad tras corte o comportamiento Android 15/16.
El inspector y limpiador de staging continúan desconectados de producción;
`exercise-images-previous-*` se preserva. PR #57 permanece DRAFT y
#56 abierta hasta los gates físicos y de recuperación pendientes.

## Protección del padre privado en galería y mutaciones USER — 2026-10-30 19:12 UTC

La revisión estática de `ExerciseImageManager` encontró que `addUserImage`,
`validatedUserImageFile` (borrado) y `safeReadableUserImageFile`
(galería/`readBytes`) validaban `exercise-images` y la entrada final, pero
no que su padre `filesDir` fuera un directorio real sin enlaces simbólicos.
Un `Context` con `filesDir` enlazado podía redirigir altas, lecturas o
borrados a un árbol ajeno. Es un **riesgo estático**, no un incidente observado.

La rama incorpora comprobación fail-closed del padre con
`Files.isDirectory(..., NOFOLLOW_LINKS)` antes de crear imágenes, antes de
validar un borrado y otra vez antes de tocar bytes después de mutar Room;
la galería oculta la referencia y `readBytes` rechaza el padre enlazado.
La compensación y la limpieza de temporales también evitan operar sobre
entradas no regulares cuando el padre ya no es seguro. La prueba Robolectric
`linkedPrivateParentRejectsImageWritesReadsAndDeletionWithoutChangingRoom`
usa `ContextWrapper`, Room en memoria y un árbol externo con bytes
centinela para verificar rechazo de alta/lectura/baja y conservación de
referencias y bytes. **CI del HEAD nuevo por verificar**.

Las comprobaciones de ruta no son una defensa atómica ante sustitución
concurrente de ancestros (TOCTOU); no acreditan durabilidad ni exclusión
entre procesos. No conectar limpieza aislada, no fusionar PR #57 ni cerrar
#56 sin Android 15/16, cortes reales, auditoría completa y regresión BF-02.

## Galería: sustitución de la raíz después de la observación — 2026-10-30 21:11 UTC

[Android CI #41120661583](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41120661583) terminó **SUCCESS** para el commit exacto `1eb6d8f`, incluida la corrección de los dos fallos de limpieza de temporales symlink del run #41113415805. Este resultado corresponde a pruebas JVM/Robolectric, lint, ensamblado debug y compilación de androidTest; no acredita ejecución instrumentada ni durabilidad real.

Nueva revisión estática de `ExerciseImageManager.readBytes()`: `safeReadableUserImageFile()` rechazaba una raíz `exercise-images` enlazada en el momento de observar, pero la ruta de lectura solo volvía a verificar `filesDir` tras `beforeUserImageRead`. Como `NOFOLLOW_LINKS` del descriptor solo protege el componente **final**, sustituir la raíz intermedia por un symlink antes de abrir permitía leer bytes externos del mismo nombre. Es un riesgo reproducible por inyección controlada, **no una exposición observada en un dispositivo real**.

El commit `3c47f81` revalida que `exercise-images` sea un directorio real con `NOFOLLOW_LINKS` inmediatamente antes de abrir. El test Robolectric `swappedImageRootSymlinkBeforeOpenCannotReadExternalBytes` (`8371795`) mueve temporalmente la raíz legítima, instala un symlink a un árbol externo con bytes centinela y exige que `readBytes` devuelva `null` sin alterar Room ni el archivo externo; restaura el árbol en `finally`. **La nueva CI debe comprobarse para el HEAD de estos cambios**, no se extrapola el SUCCESS anterior.

Esta defensa no es atómica frente a un adversario que cambie el directorio entre la nueva comprobación y `newByteChannel`. Siguen pendientes auditoría global de lectores/DAO y carreras TOCTOU, Android API 35/36, muerte de proceso/cortes, tratamiento seguro de `-previous-*` y regresión física BF-02. Mantener inspector/limpiador desconectados y PR #57 DRAFT.

## Revalidación de raíz durante exportación de backup — 2026-10-30 22:07 UTC

La inspección de `UserExerciseImageStore.readRequired()` encontró otra ventana
determinista: tras validar `exercise-images` y el archivo USER, el seam
`beforeRequiredImageRead` permitía sustituir la **raíz** por un enlace
simbólico antes de `Files.newInputStream(file, NOFOLLOW_LINKS)`. Esa opción
solo impide seguir el enlace del último componente, no el directorio
intermedio; la exportación podía leer bytes externos sin modificar Room.

En PR #57, `e681f84` revalida `filesDir` y exige que la raíz siga siendo
un directorio real (`NOFOLLOW_LINKS`) inmediatamente antes de abrir el
descriptor. `e686e35` añade una regresión Robolectric que aparta
temporalmente la raíz, la sustituye por un enlace a un directorio con bytes
externos y exige fallo cerrado, preservación de los bytes externos,
referencias Room y preferencias; después restaura el fixture original.

**Estado:** commits publicados, CI del nuevo HEAD todavía por confirmar.
La revalidación reduce una ventana reproducible pero no elimina una carrera
TOCTOU entre la última comprobación y la apertura, ni demuestra aislamiento
de otros procesos, durabilidad tras corte o ejecución Android API 35/36.
No se activa limpieza de staging ni se toca `exercise-images-previous-*`.
Mantener #56 abierta y PR #57 DRAFT hasta completar los gates.

## Revalidación del directorio de staging durante escritura — 2026-10-30 23:10 UTC

La inspección de `UserExerciseImageStore.stageReplacement()` identificó una ventana
determinista: tras crear `exercise-images-import-<UUID>`, el seam
`beforeStagedImageWrite` podía sustituir **el directorio staging** por un
symlink a otro árbol. `FileChannel.open(target, CREATE_NEW, NOFOLLOW_LINKS)`
protege el fichero final, pero **no** los componentes intermedios y podía
crear bytes en ese árbol externo. Es un riesgo estático reproducible mediante
inyección, **sin incidente observado en un dispositivo real**.

El commit `eb85577` añade `requireSafeStaging`: comprueba que tanto
`filesDir` como el directorio staging siguen siendo directorios reales
(`NOFOLLOW_LINKS`) después del seam, después de sincronizar cada imagen
y antes de sincronizar las entradas de directorio. El commit `0a62342`
añade una regresión Robolectric que cambia el staging por un enlace antes
de abrir el fichero y exige fallo cerrado, sin crear bytes externos ni
alterar Room, preferencias o imágenes anteriores. El enlace de prueba se
retira explícitamente sin seguirlo; la limpieza de producción mantiene
su rechazo conservador ante staging sustituido.

**Estado:** CI del nuevo HEAD por confirmar. Las comprobaciones por ruta
siguen siendo vulnerables a carreras TOCTOU entre el último chequeo y la
apertura; no sustituyen aislamiento por descriptores, exclusión entre
procesos, pruebas de durabilidad/cortes ni ejecución instrumentada API35/36.
No activar el limpiador aislado ni borrar `exercise-images-previous-*`.
#56 continúa abierta y PR #57 en DRAFT, pendiente también de BF-02 física.

## Activación de staging tras sincronización — 2026-10-31 00:14 UTC

La [CI #41142922249](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41142922249) finalizó **SUCCESS** para el HEAD anterior `9213e6d`: job `verify` (JVM/Robolectric, lint, debug y compilación de androidTest); **no ejecuta pruebas Android instrumentadas**.

Hallazgo adicional en `StagedImageReplacement.activate()`: aunque `stageReplacement()` revalidaba staging antes de devolverlo, entre la sincronización y la activación (incluido el punto de inyección `BEFORE_IMAGES_ACTIVATED`) podía sustituirse el directorio `exercise-images-import-<UUID>` por un enlace simbólico. `renameTo(current)` trasladaría entonces el **enlace** a la ruta activa. No consta incidencia real; es una ventana reproducible de manipulación de rutas.

La corrección `e51b971` comprueba con `NOFOLLOW_LINKS` que el padre privado y staging sean directorios reales antes de mover la raíz antigua y **de nuevo** inmediatamente antes de activar staging, después de la sincronización de la copia preservada. La regresión Robolectric `23a7f54` usa `importFault(BEFORE_IMAGES_ACTIVATED)` para sustituir staging por symlink tras su sincronización y exige fallo cerrado, sin modificar Room, tema ni bytes originales/externos. La segunda regresión `8b71e89` sustituye staging **después** de apartar la raíz antigua, durante la sincronización del directorio padre, y verifica que el rollback restaure la raíz previa sin alterar Room, tema ni bytes externos. Los archivos de prueba se retiran sin seguir el enlace. **CI de estos nuevos commits pendiente de comprobar**.

Esta revalidación de rutas no proporciona apertura/rename atómicos por descriptores ni elimina carreras TOCTOU; tampoco demuestra recuperación tras muerte real de proceso, durabilidad física o ejecución API 35/36. Se preservan `exercise-images-previous-*`, y el inspector/limpiador siguen desconectados. Mantener #56 abierta y PR #57 DRAFT; BF-02 física y los gates restantes siguen pendientes.


## Journal: intercambio del directorio entre preflight y descriptor — 2026-10-31 01:10 UTC

La [CI #41149156884](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41149156884) terminó **SUCCESS** para el HEAD previo `8329e10` (job `verify`), con JVM/Robolectric, lint y compilaciones previstas por el workflow; **sin Android instrumentado ni pruebas de corte real**. Esto valida las dos regresiones de activación de staging publicadas en `23a7f54`/`8b71e89`, no los cambios posteriores.

La auditoría de `BackupRecoveryJournal.readTrustedFile()` y `writeSynced()` detectó otra ventana: `NOFOLLOW_LINKS` en el archivo `state`/`previous.zip` o temporal impide seguir el **último** componente, pero no un directorio `backup-recovery` o `backup-recovery.preparing` sustituido por symlink después del preflight. Una inyección determinista podía redirigir la lectura o la creación de temporales hacia un directorio externo. **No se ha observado un incidente real.**

El commit `c199fc4` revalida con `Files.isDirectory(..., NOFOLLOW_LINKS)` el directorio padre privado y el directorio inmediato del journal **después** de los seams de lectura/escritura y antes de abrir el descriptor. `8b855a6` incorpora dos regresiones JVM: sustitución del journal activo por enlace a otro journal válido antes de leer `state`, y sustitución de `backup-recovery.preparing` por enlace antes de crear `previous.zip.tmp`. `9202983` añade una tercera para sustitución del journal activo antes de crear `state.tmp` durante COMMITTED. Exigen rechazo y preservación de journals y archivos externos. **CI del HEAD nuevo pendiente de comprobar**.

Esta defensa reduce una carrera determinista pero **no** es atómica: otro actor puede sustituir un ancestro entre el chequeo y `FileChannel.open`. Tampoco cubre todavía todas las operaciones de `renameTo`, limpieza, aislamiento entre procesos ni durabilidad bajo corte. El journal y la recuperación permanecen pendientes de Android API 35/36, pruebas de muerte/corte real, auditoría completa de lectores/DAO/procesos y regresión física BF-02. No habilitar limpieza automática ni borrar `exercise-images-previous-*`; mantener #56 abierta y PR #57 DRAFT.

## Publicación PREPARED tras fsync: raíz de staging sustituida — 2026-10-31 02:15 UTC

[Android CI #41154097310](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41154097310)
finalizó **SUCCESS** para `c9317f3`: JVM/Robolectric, lint y compilaciones del
job `verify`, **sin pruebas instrumentadas Android 15/16 ni cortes reales**.
Valida las tres regresiones anteriores del journal, no los cambios de esta sección.

Se encontró una ventana adicional en `BackupRecoveryJournal.prepare()`:
tras escribir y sincronizar `previous.zip` y `state` dentro de
`backup-recovery.preparing`, el método publicaba el directorio con
`renameTo(backup-recovery)` sin revalidar que staging siguiera siendo un
directorio real. Una sustitución por symlink después del último fsync podía
publicar un enlace como journal PREPARED. No hay incidente real confirmado.

El commit `6f076de` revalida el directorio de staging y su padre con
`NOFOLLOW_LINKS`, rechaza un journal activo aparecido concurrentemente
(incluidos enlaces colgantes) y solo después intenta publicar PREPARED.
`8f06197` añade una regresión JVM con inyección durante el segundo fsync
de staging: comprueba rechazo, ausencia de journal activo, conservación de
los archivos preparados y de bytes externos. **Pendiente CI del HEAD nuevo**.

Estas comprobaciones no son atómicas ante cambios entre la última
validación y `renameTo`, ni demuestran persistencia de metadatos tras
corte de energía. Mantener #56 abierta, PR #57 DRAFT sobre #50,
inspector/limpiador desconectados y `exercise-images-previous-*`
preservados. Siguen pendientes API35/36 instrumentado, cortes reales,
auditoría global de DAO/lectores/procesos y BF-02 física.

## Publicación segura de temporales de journal — 2026-10-31 03:10 UTC

La [CI #41159132133](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41159132133) terminó **SUCCESS** para el HEAD anterior `6bc3a4b` (verificación JVM/Robolectric, lint y compilación; no ejecuta Android instrumentado). En la revisión de `BackupRecoveryJournal.writeSynced()` se identificó otra ventana determinista: el descriptor `CREATE_NEW + NOFOLLOW_LINKS` asegura la escritura del temporal, pero después de su `force(true)` el `renameTo` publica de nuevo la ruta, que podía haberse sustituido por un enlace simbólico. Esto podía promocionar un enlace como `previous.zip` o `state` y bloquear una recuperación posterior; no hay incidente real confirmado.

El commit `3607441` añade una revalidación fail-closed del directorio, temporal regular sin seguir enlaces y destino no enlazado justo antes de publicar; `8dcfb91` añade dos pruebas JVM de sustitución después del fsync, antes de publicar PREPARED y COMMITTED, con preservación de los bytes externos y del PREPARED previo. **Estos cambios requieren CI del HEAD nuevo; no se atribuye el SUCCESS anterior.** Las comprobaciones por ruta siguen sin resolver la carrera TOCTOU atómica, y tampoco prueban durabilidad ante corte, Android 15/16 ni ausencia de accesos DAO directos. PR #57 permanece DRAFT, #56 abierta; no conectar limpiador ni borrar `exercise-images-previous-*`.

## Journal: directorio privado padre enlazado — 2026-10-31 04:07 UTC

Auditoría estática de `BackupRecoveryJournal` en PR #57: `cleanupRetired()` y
`cleanupAbandonedPreparation()` rechazaban symlinks en el componente final
(`recovery.retired`/`recovery.preparing`), pero `cleanupJournalArtifacts()`
podía enumerar y eliminar ficheros regulares **fuera del área privada** si el
directorio padre había sido sustituido por un enlace simbólico. Además,
`prepare()` podía crear `recovery.preparing` a través del padre enlazado
antes de que las validaciones posteriores de escritura rechazasen la ruta.
Se trata de un riesgo estático con fixture controlado; no consta pérdida real.

Corrección `b0022ce`: verificar el padre real con `NOFOLLOW_LINKS` antes
de inspección/creación, y validar el directorio y su padre antes de borrar
cada artefacto y retirar la carpeta. Regresiones JVM `5152efa`: padre
enlazado no crea staging externo y no elimina artefactos externos de
`retired`/`preparing`. Los tests son de JVM; **CI del nuevo HEAD pendiente
de comprobar**, sin Android físico ni prueba de cortes. Las comprobaciones
de rutas no son atómicas frente a un adversario que cambie ancestros entre
la comprobación y la operación (TOCTOU); tampoco acreditan exclusión entre
procesos. No conectar limpiadores, no borrar `exercise-images-previous-*`
ni fusionar PR #57 hasta cumplir los gates de #56.

## Alta de imagen USER: sustitución de raíz entre preparación y publicación — 2026-10-31 05:09 UTC

[Android CI #41167341834](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41167341834) terminó **SUCCESS** para el HEAD previo `9023332` (JVM/Robolectric, lint y compilaciones, sin instrumentación Android ni prueba de corte real).

La auditoría de `ExerciseImageManager.addUserImageUnprotected()` detectó que, tras comprobar la raíz `exercise-images`, la ruta de alta abría un temporal con `NOFOLLOW_LINKS` únicamente en el componente final. El seam `beforeUserImageWrite` permitía sustituir la **raíz intermedia** por un symlink antes de abrirlo. Asimismo, `afterUserImageSync` permitía intercambiar esa raíz antes de validar/publicar la imagen y su referencia Room. Son ventanas reproducibles con fixtures controladas; no se ha observado incidente real.

El commit `31ed962` añade `requireSafeImageDirectory()` después de ambos seams y alrededor de la publicación/sincronización, rechazando raíces sustituidas o enlazadas. `a7a0430` incorpora dos regresiones Robolectric con raíz intercambiada antes de abrir el descriptor y después del fsync; exigen que no se cree referencia Room ni se modifique el árbol externo. **La CI de este HEAD todavía debe verificarse**; el SUCCESS anterior no valida estos cambios.

Estas verificaciones de ruta **no resuelven TOCTOU atómico** entre la última comprobación y apertura/rename, ni acreditan durabilidad en Android API 35/36, exclusión entre procesos o cortes de energía. No conectar el limpiador aislado, no eliminar `exercise-images-previous-*`, mantener #56 abierta y PR #57 en DRAFT. Se requiere ejecución instrumentada y regresión física BF-02 antes de integrar.

## Borrado de imagen USER: raíz sustituida tras eliminar fila Room — 2026-10-31 06:10 UTC

En `ExerciseImageManager.deleteUserImageUnprotected()` se verificaba la ruta
antes de `exerciseDao.deleteImage(image)`, pero tras confirmar el borrado
Room solo se volvía a comprobar `filesDir`. Si `exercise-images` pasaba a
ser un enlace simbólico entre ambas operaciones, la eliminación posterior
podía actuar sobre un archivo externo con el mismo nombre. No consta
incidente real.

El commit `26e1de9` comprueba la raíz intermedia y el componente final antes
de retirar bytes y vuelve a comprobar la raíz tras el callback de borrado.
Ante una raíz sustituida conserva la fila Room para permitir recuperación,
sin seguir ni borrar archivos del destino externo. El commit `670a4d3`
añade una regresión Robolectric que sustituye la raíz justo después de
borrar la fila, verifica que el archivo externo permanece intacto y que
la referencia original puede leerse al restaurar la raíz privada.

**Limitación:** la comprobación por rutas no es una operación atómica
resistente a un adversario que cambie el filesystem entre comprobación
y `delete()`; tampoco demuestra durabilidad ante caída abrupta. Esta
corrección no sustituye los gates Android 15/16, pruebas de cortes, revisión
global de DAO/lectores ni regresión física BF-02. CI del nuevo HEAD
pendiente de verificar; PR #57 continúa en DRAFT y #56 abierta.

## Reconciliación de huérfanas: sustitución de raíz tras consulta DAO — 2026-10-31 07:11 UTC

La CI del HEAD anterior `fc31ff7`, [Android CI #41175629179](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41175629179), finalizó **SUCCESS** (verificación ordinaria JVM/Robolectric/lint/compilaciones; no instrumentación Android ni prueba de corte). Esta ejecución no valida los cambios descritos aquí.

La inspección de `UserImageOrphanReconciler.reconcile()` encontró que la raíz `exercise-images` se validaba antes de enumerar archivos y consultar Room, pero no se revalidaba justo antes de `removeFile(file)`. Si un actor sustituyera la raíz por un symlink después de la consulta DAO, el borrado podría seguir el directorio intermedio y eliminar un archivo externo con el mismo nombre UUID. Es un riesgo estático reproducible mediante inyección determinista; **no consta incidente real**.

El commit `2117727` añade una comprobación fail-closed del padre privado, raíz intermedia y archivo regular con `NOFOLLOW_LINKS` inmediatamente antes del borrado; también incorpora un seam de prueba `beforeOrphanRemoval`. El commit `40ab5f2` añade una regresión Robolectric que intercambia la raíz tras la comprobación DAO, exige preservar bytes externos y permite reintentar la reconciliación después de restaurar la raíz original. **CI del HEAD nuevo pendiente de comprobar.**

Estas comprobaciones por ruta no son atómicas frente a un cambio concurrente entre validación y `delete()`; no demuestran aislamiento entre procesos ni durabilidad física. Se mantienen los gates Android 15/16, cortes reales, revisión global DAO/lectores/procesos y regresión BF-02. No activar el limpiador aislado ni borrar `exercise-images-previous-*`; #56 y PR #57 permanecen abiertas en DRAFT.

## Compensación de inserción de imagen USER ante raíz sustituida — 2026-10-31 08:10 UTC

La revisión de `ExerciseImageManager.addUserImageUnprotected()` identificó una
ruta distinta de las escrituras/borrados normales: cuando el DAO inserta la
fila y luego falla `afterImageRowInsert`, la compensación `NonCancellable`
elimina primero la fila y después intenta retirar el fichero publicado.
Antes de `b457af1` se comprobaba `filesDir` y el componente final con
`NOFOLLOW_LINKS`, pero **no** que el directorio intermedio
`exercise-images` continuase siendo real. Si ese directorio se sustituía
por un symlink a un árbol con un archivo del mismo UUID, la compensación
podía borrar el archivo externo. Hallazgo de análisis de código y
reproducción determinista preparada; **no se confirma incidente real**.

- `b457af1` exige raíz intermedia real `NOFOLLOW_LINKS` antes del borrado
  compensatorio. Ante sustitución, falla cerrado: puede retener un huérfano
  local recuperable, pero no sigue el enlace.
- `e8ea904` añade regresión Robolectric con cambio de raíz tras insertar
  la fila Room y antes del fallo inyectado; comprueba preservación de bytes
  externos, compensación de metadatos y posibilidad de nueva inserción.
- La prueba queda **pendiente de resultado CI del HEAD**. La comprobación
  de rutas por nombre no es una operación atómica: persiste una carrera
  TOCTOU entre validación y `File.delete()`; no afirmar aislamiento fuerte
  entre procesos ni resistencia frente a sustituciones concurrentes.
- Continúan pendientes API 35/36 instrumentada, durabilidad ante corte
  real, auditoría exhaustiva DAO/lectores/procesos y regresión física BF-02.
  Mantener #56 abierta, PR #57 DRAFT sobre #50, limpiador/inspector
  aislados y `exercise-images-previous-*` preservado.

## Recuperación fallida en arranque y reapertura accidental del barrier — 2026-10-31 09:08 UTC

Revisión de `BarrierProtectedJournaledBackupDataSource.recoverBeforeDataAccess()`:
antes de esta corrección, solo se invocaba `markRecovered()` tras el replay,
pero el `BackupDomainWriteBarrier` se inicializa con `recoveryRequired = false`.
Si el replay de un journal PREPARED fallaba antes de que la app quedara
habilitada, la barrera **por sí sola** permitía a escritores cooperantes
reanudar mutaciones; la puerta de arranque de `DefaultAppContainer` seguía
siendo la protección principal, pero no debía asumirse como única defensa.
Es un fallo de defensa en profundidad, no una pérdida de datos confirmada.

`8f044f0` marca la recuperación como requerida **antes** de invocar el
replay, dentro del mismo mutex exclusivo; solo un replay exitoso la libera.
`1e9e6ac` añade una regresión JVM con journal PREPARED y fallo de
restauración simulado: verifica que las escrituras y exportaciones quedan
bloqueadas y que un reintento correcto restaura el estado y abre la barrera.
La nueva CI del HEAD debe verificarse antes de declarar el test validado.

Esto no sustituye la puerta de arranque, el aislamiento entre procesos,
el control de lectores ya compuestos, las pruebas de durabilidad/corte
real ni la instrumentación Android API35/36. Mantener #56 abierta,
PR #57 DRAFT sobre #50 y las restricciones sobre limpieza y BF-02.

## Cancelación del replay de arranque — 2026-10-31 10:13 UTC

Se añade una regresión JVM en `BarrierProtectedJournaledBackupDataSourceTest`
(commit `04ab8aa`) para cancelar el primer replay de un journal `PREPARED`
cuando el adaptador todavía está suspendido. Comprueba que el journal sigue
`PREPARED`, el dominio parcial no se exporta, los escritores cooperantes
permanecen bloqueados y un segundo replay completo restablece los datos y
reabre la barrera. El test usa una suspensión determinista en memoria; **no
simula muerte de proceso, corte de energía ni Android real**. No acredita
cobertura de escritores ajenos a la barrera ni resuelve TOCTOU o los gates
físicos API 35/36. PR #57 continúa DRAFT y #56 abierta.

## Limpieza de marcador COMMITTED interrumpido: raíz intercambiada — 2026-10-31

Revisión de `BackupRecoveryJournal.markCommitted()`: después de `inspect()`,
la retirada de `state.tmp` verificaba únicamente que el componente final no
fuera un symlink. Una sustitución del directorio intermedio `recovery` por
un enlace a un directorio externo con un `state.tmp` regular permitía borrar
ese archivo externo antes de que `writeSynced()` rechazase la raíz insegura.
**Hallazgo estático con reproducción determinista; no consta incidente real.**

- `963bb02` vuelve a comprobar la raíz y su padre con `NOFOLLOW_LINKS`
  antes de inspeccionar y eliminar el marcador temporal; exige fichero regular
  sin seguir enlaces y revalida la raíz antes del borrado. No elimina ni
  sobreescribe `PREPARED` si falla la comprobación.
- `b6797dd` incorpora regresión JVM con sustitución de raíz exactamente tras
  `inspect()`: exige preservar el `state.tmp` externo, mantener intacta la
  preimagen y permitir confirmar tras restaurar el directorio privado.
- **CI del HEAD pendiente de verificar.** Las comprobaciones por ruta no son
  defensa atómica frente a TOCTOU, no prueban Android 15/16, durabilidad ante
  cortes ni aislamiento entre procesos. Se mantienen #56 abierta y PR #57
  DRAFT sobre #50; no conectar limpiadores, no borrar `-previous-*`, no
  fusionar ni publicar APK sin completar los gates restantes.

## Limpieza de journal retirado: cambios entre preflight y borrado — 2026-10-31 12:10 UTC

La revisión de `BackupRecoveryJournal.cleanupJournalArtifacts()` detectó que
las entradas se validaban como ficheros durante el preflight, pero antes de
cada `File.delete()` solo se revalidaba el directorio padre/raíz. Una entrada
sustituida por un directorio vacío podía eliminarse pese a no ser un
artefacto plano válido; una raíz sustituida por symlink tras el preflight
exige rechazo explícito antes de intentar borrar. No consta incidente real.

- `4018866` vuelve a exigir que cada entrada sea un fichero regular con
  `NOFOLLOW_LINKS` y no un symlink inmediatamente antes del borrado.
- `f4880f9` añade dos regresiones JVM deterministas: sustitución de la raíz
  retirada por un enlace a archivos externos, y sustitución de una entrada
  por un directorio después del preflight. Exigen preservar los bytes ajenos
  y permitir reintentar la limpieza del journal original.
- **CI del HEAD pendiente de comprobar**. Los chequeos por ruta no garantizan
  atomicidad ante un atacante concurrente que intercambie componentes entre
  la última comprobación y `delete()`. Mantener la PR #57 en DRAFT y la
  Issue #56 abierta. Siguen pendientes API35/36, durabilidad/cortes reales,
  auditoría global DAO/lectores/procesos y regresión física BF-02.

## Fallo de fsync al retirar COMMITTED — 2026-10-31 15:14 UTC

[Android CI #41208652180](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41208652180) finalizó **SUCCESS** para `0317e40` (job `verify`: tests JVM/Robolectric, lint, ensamblado debug y compilación de fuentes androidTest; **0 artefactos, sin instrumentación Android**). Los ocho fallos de inyección de `syncDirectory` de la ejecución anterior quedaron corregidos en ese SHA; no atribuir este resultado a commits posteriores.

El test JVM `failed fsync after retiring committed journal is retryable without restoring old data` (`2a4b6bd`) inyecta un fallo de sincronización del directorio padre **después** de renombrar el journal `COMMITTED` a `recovery.retired`, pero antes de eliminar sus archivos. Exige que la primera limpieza comunique el error, que la preimagen retirada se conserve y que un nuevo arranque retire únicamente los artefactos obsoletos, **sin restaurar el dominio anterior**. Cubre una ventana adicional de limpieza idempotente; no modifica producción.

**Límites:** el test simula un error de fsync mediante callback JVM, no verifica durabilidad real de metadatos tras corte eléctrico o muerte de proceso. Su CI nueva debe confirmarse para el HEAD exacto. No habilita limpieza automática de staging ni eliminación de `exercise-images-previous-*`. Continúan pendientes Android 15/16 instrumentado, cortes reales, TOCTOU atómico, revisión DAO/lectores/procesos y regresión física BF-02. #56 abierta y PR #57 DRAFT sobre #50.

## Sustitución por fichero regular tras fsync — 2026-10-31 16:12 UTC

En `BackupRecoveryJournal.writeSynced()` la validación posterior a `beforeTrustedPublish`
rechazaba enlaces simbólicos, pero aceptaba otro **fichero regular** de igual tamaño
que hubiera reemplazado el temporal ya sincronizado. El `renameTo` podía publicar
un `previous.zip` o `state` distinto del contenido recién escrito; un journal
corrupto debe fallar cerrado, pero no debe promoverse deliberadamente.

- `bf631de` verifica tamaño y SHA-256 de los bytes del temporal mediante descriptor
  `NOFOLLOW_LINKS` antes del rename, sin duplicar en memoria un backup de hasta 100 MiB.
- `ad8a356` añade dos regresiones JVM para sustitución por fichero regular del
  archivo previo y del marcador COMMITTED después del fsync.
- La verificación **no es atómica** frente a intercambios concurrentes entre
  lectura y rename. No habilita limpiadores ni elimina `exercise-images-previous-*`.
- CI del nuevo HEAD pendiente; conservar PR #57 DRAFT y #56 abierta. Permanecen
  Android API35/36, cortes reales/durabilidad, auditoría DAO/lectores/procesos,
  TOCTOU atómico y regresión física BF-02.

## Puerta de arranque: barrier abierto tras fallo de recuperación/seed/reconciliación — 2026-10-31 17:08 UTC

La composición de producción `DefaultAppContainer` invoca `BackupStartupGate.awaitReady()` con el `BackupDomainWriteBarrier` compartido, pero usa deliberadamente un adaptador de recuperación sin wrapper para evitar un mutex recursivo. La corrección previa en `BarrierProtectedJournaledBackupDataSource.recoverBeforeDataAccess()` marca `recoveryRequired` antes del replay, **pero ese método no se llama desde el arranque**. Por tanto, si el replay, el seed o la reconciliación fallaban, el mutex exclusivo se liberaba con `recoveryRequired=false`; un escritor cooperante que accediera al repositorio sin pasar por `awaitReady()` podía mutar datos mientras la puerta de UI seguía cerrada. Es un defecto de defensa en profundidad por inspección del cableado, **sin incidente real confirmado**.

La corrección `14e0095` marca `recoveryRequired` al entrar en la sección exclusiva de `BackupStartupGate` y solo lo retira tras recuperar, sembrar y reconciliar correctamente. La regresión `161306c` actualiza la expectativa previa que daba por válido un writer tras un replay fallido y añade escenarios de fallo de seed y reconciliación; comprueba bloqueo hasta un reintento completo. **CI del HEAD nuevo pendiente de comprobar**: no atribuir éxito a estas pruebas hasta verificar su ejecución.

Esto protege solo a escritores que usan la barrera compartida. No demuestra protección de acceso directo a DAO, lectores ya montados, otros procesos, durabilidad ante corte ni TOCTOU atómico. Se mantienen PR #57 DRAFT sobre #50, #56 abierta y gates Android API35/36, cortes reales y regresión física BF-02. No habilitar limpiadores aislados ni borrar `exercise-images-previous-*`.
