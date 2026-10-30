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
