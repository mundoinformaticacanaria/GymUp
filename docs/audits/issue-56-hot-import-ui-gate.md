# #56 — Auditoría del gate de UI durante importación en caliente

Estado: **integración experimental del gate Compose en PR #57; validación CI y pruebas instrumentadas pendientes**. Revisión inicial del 2026-10-29.
Alcance: PR #57, sin modificar el candidato BF-02.

## Rutas verificadas

- `GymUpApp` llama a `AppContainer.awaitReady()` antes de componer el árbol de navegación, pero `StartupState.Ready` permanece listo durante importaciones posteriores.
- `BackupSection` ejecuta `BackupManager.importReplaceAll` desde un `rememberCoroutineScope()` **propiedad de esa composición**. Desmontar `SettingsScreen` o su `NavHost` cancela ese trabajo.
- `BarrierProtectedJournaledBackupDataSource` serializa la sustitución y recuperación con escritores cooperantes, pero **no** suspende las lecturas ni los `Flow` de repositorios/DAO ya activos en la UI.
- Tras un rollback que también falla, `BackupDomainWriteBarrier` impide nuevas escrituras y exportaciones, pero no desmonta los consumidores ya compuestos. La UI puede mostrar una mezcla transitoria de datos previos y nuevos.

## Riesgo al implementar el gate

No basta con añadir un `StateFlow<Importing>` a la barrera y ocultar `GymUpReadyApp` al empezar: el import se inició desde `rememberCoroutineScope` de `BackupSection`. Desmontar la composición cancelaría el propio import a mitad de la sustitución. El journal y la recuperación mitigan el daño, pero esta cancelación inducida por el gate es evitable y no debe introducirse como solución.

## Diseño exigido antes de marcar la puerta como resuelta

1. Elevar la operación confirmada a un coordinador de importación con ciclo de vida **independiente de la pantalla** (por ejemplo, scope supervisado de aplicación). El picker y la confirmación permanecen en UI; la operación de reemplazo y recuperación no depende de que `SettingsScreen` siga compuesta.
2. Publicar estado observable de importación: preparación, reemplazo exclusivo, recuperación, éxito, rechazo validado y fallo con recuperación pendiente. No declarar `Ready` mientras pueda verse un estado parcial.
3. Ocultar o desmontar consumidores de Room/DataStore/imágenes antes de permitir que el reemplazo modifique el dominio, y mantenerlos desmontados hasta concluir recuperación. Al finalizar correctamente, recrear los ViewModels/flows y la navegación con una nueva generación de datos; no reutilizar estado cacheado de la sesión anterior.
4. Ante recuperación fallida, mantener pantalla cerrada con una vía de reintento controlada; no permitir navegación ni lecturas directas hasta recuperación verificada. No presentar como éxito una importación que ha lanzado error.
5. Asegurar que cancelación de la pantalla, rotación y recreación de Activity no cancelan la operación de dominio. Evitar scope global sin supervisión o trabajo concurrente duplicado.
6. Añadir pruebas deterministas de importación con escritor/lector en espera, cancelación de la pantalla, recuperación fallida, reintento y reentrada limpia de la UI; validar luego cortes físicos Android 15/16.

## Cobertura incremental de esta pasada

El test JVM `cancelling hot import after mutation restores preimage before releasing writers` en `BarrierProtectedJournaledBackupDataSourceTest` comprueba una **red de seguridad**, no la solución de UI: si el trabajo se cancela después de mutar el delegate, el rollback termina antes de reanudar al escritor cooperante y el journal queda limpio. Su resultado depende de Android CI del commit que lo incorpora.

## Puertas que siguen abiertas

La composición Compose ahora se desmonta antes del reemplazo, pero no se ha acreditado el aislamiento de todos los accesos DAO/lectores fuera de la UI. Siguen pendientes limpieza recuperable de imágenes tras baja de ejercicio, auditoría completa de accesos directos, durabilidad física y prueba de corte real. #56 y PR #57 deben permanecer abiertas/en borrador. No generar APK ni fusionar por esta evidencia.

## Infraestructura aislada del coordinador — 2026-10-29 02:14 UTC

Se incorpora `HotBackupImportCoordinator` con scope inyectado, handshake explícito de lectores desmontados, estados observables y recuperación no cancelable tras fallo, más cuatro pruebas JVM de espera, cancelación de pantalla, duplicados y reintento tras recuperación fallida. **Todavía no está conectado a `DefaultAppContainer`, `BackupSection` ni `GymUpApp`**: no hay gate productivo de lecturas ni se modifica el flujo actual de importación. El siguiente paso es integrar la propiedad del scope en la aplicación, confirmar el desmontaje real de Compose antes de llamar `readersDisposed` y recrear la navegación/flows tras éxito o recuperación. No declarar esta infraestructura como aislamiento de UI ni cerrar #56.

## Integración del gate de Compose — 2026-10-29 03:11 UTC

La composición productiva conecta el coordinador al `DefaultAppContainer` con scope
supervisado de aplicación y deriva la importación confirmada de `BackupSection`
a `HotBackupImportCoordinator.start` (sin `rememberCoroutineScope` para reemplazo).
`GymUpApp` desmonta el `NavHost` durante `ClosingReaders` y confirma el
handshake con `SideEffect` después de aplicar el cambio de composición; mantiene
la interfaz cerrada durante importación, recuperación y fallo persistente.
Al completar, recrea navegación y ViewModels por generación, y presenta el
resultado de éxito, rechazo o error. Un nuevo test JVM exige otro handshake
para una segunda importación. **La CI de este cambio aún debe validarse.**

Esto cubre el gate de lectores Compose cooperantes, **no** garantiza que todos
los accesos DAO directos estén protegidos ni la durabilidad tras muerte real
del proceso. Permanecen pendientes limpieza recuperable de imágenes, auditoría
DAO, pruebas instrumentadas de rotación/cancelación y cortes físicos Android 15/16.

## Recreación de pantalla y límites de concurrencia — 2026-10-29 04:10 UTC

Se añaden dos pruebas **JVM del coordinador**, no pruebas instrumentadas de
rotación de `Activity` ni del árbol Compose: (1) cancelar el scope de una
pantalla antes de reconocer el desmontaje conserva `ClosingReaders`, impide
la importación y rechaza duplicados hasta el reconocimiento explícito;
(2) durante una recuperación suspendida el estado sigue siendo `Recovering`
y no admite otra importación hasta concluir. Estas pruebas verifican la
máquina de estados, **no** demuestran que el árbol real haya liberado todos
sus lectores durante una rotación.

Riesgo adicional para la auditoría: `readersDisposed()` confirma un único
handshake de composición. La aplicación aún no acredita un recuento global de
lectores si coexisten varias instancias de `Activity` (por ejemplo, ventanas
múltiples), ni que no queden trabajos de lectura fuera del ciclo de vida
de los ViewModels. Antes de cerrar #56, instrumentar rotación/recreación
real y verificar que ninguna lectura/DAO ni escritura no cooperante cruza
el reemplazo. No inferir aislamiento completo de la CI JVM.

## Inventario de accesos Room desde la composición — 2026-10-29 04:12 UTC

Revisión estática en el HEAD `0b9732f` de `AppContainer`, repositorios
Room, seeder, limpieza, preferencias, imágenes y 15 archivos de pantallas/
ViewModels/use case de `feature/` y `domain/usecase/`:

| Entrada productiva | Escritura | Protección comprobada |
| --- | --- | --- |
| `SessionRepository` / `RoutineRepository` | Sesiones, series, rutinas | `BarrierProtectedSessionRepository` / `BarrierProtectedRoutineRepository` en `DefaultAppContainer` |
| `ExerciseCatalogRepository` / `CatalogMaintenanceRepository` | Favoritos, maestros, ejercicios | Adaptadores `BarrierProtected*` en `DefaultAppContainer` |
| `ExerciseImageManager` | Archivo privado y fila Room | `domainWriteBarrier` inyectada en producción |
| `UserPreferencesRepository` | Tema DataStore | Misma barrera en instancia expuesta; instancia cruda solo para backup |
| `HistoricalCleanupManager` | Borrado de histórico | `BarrierProtectedHistoricalCleanupStore` |
| `DatabaseSeeder` | Inserciones iniciales | `BackupStartupGate` bajo exclusión antes de UI |
| `RoomBackupDataSource` | Reemplazo Room, imágenes, preferencias | Instancia cruda solo dentro de `BarrierProtectedJournaledBackupDataSource` |
| `RoomMasterCatalogRepository` / `RoomHistoryRepository` | Ninguna en sus interfaces revisadas | Lecturas; no están bajo mutex |

No se encontraron construcciones directas de repositorios Room, `GymUpDatabase`
ni accesos DAO en los 15 archivos de UI/use case examinados. Esto **no**
prueba ausencia de todas las rutas de acceso en el proyecto, y las lecturas
en curso no están protegidas por el mutex de escritores: dependen del gate
de composición y de su desmontaje.

**Riesgo material confirmado:** `RoomCatalogMaintenanceRepository.deleteExercise`
confirma Room y solo después retira archivos USER; aunque la operación está
serializada con importación, un corte en esa ventana deja imágenes huérfanas.
La recuperación del journal de importación no reconstruye esa limpieza.
Se requiere protocolo idempotente propio o recolección reconciliable,
pruebas de corte/reinicio y validación de rutas. No cerrar #56 ni fusionar
PR #57 por esta auditoría parcial.

## Auditoría de claves USER compartidas y borrado parcial — 2026-10-29 05:20 UTC

La tabla `exercise_images` solo impone unicidad sobre `(exercise_id, position)`,
**no sobre `storage_key`**. Por ello dos filas distintas (incluso de ejercicios
diferentes) pueden referenciar el mismo archivo privado. Antes de esta corrección,
`RoomCatalogMaintenanceRepository.deleteExercise` y
`ExerciseImageManager.deleteUserImage` podían eliminar bytes todavía
referenciados por otra fila. Se añaden consultas de recuento y una comprobación
**anterior a cualquier borrado de Room**: solo se permite retirar el archivo
cuando existe exactamente una referencia USER a esa clave. Dos pruebas
Robolectric inyectan duplicados y comprueban que se preservan filas y bytes.

También se incorporan dos pruebas de caracterización de la ventana restante:
si falla la retirada de la segunda imagen de un ejercicio, la primera ya puede
haberse eliminado y Room haber confirmado la baja; si falla el fsync del
directorio después del borrado, la operación comunica error pero no demuestra
durabilidad. **Estas pruebas no son recuperación**. Queda pendiente diseñar
y conectar un protocolo persistente de limpieza/reconciliación que no borre
imágenes válidas ni siga enlaces, antes de cerrar #56.

La comprobación de unicidad es transaccional para la baja de ejercicio, pero
no sustituye exclusión entre procesos ni garantiza atomicidad de la operación
completa Room/FS. La validación CI de los nuevos commits está pendiente;
sin pruebas físicas ni autorización para fusionar o generar APK.

## Reconciliación idempotente de imágenes huérfanas al arranque — 2026-10-29 06:15 UTC

La PR #57 incorpora `UserImageOrphanReconciler` y consulta DAO de claves
`USER`. `DefaultAppContainer` lo ejecuta mediante `BackupStartupGate`
**después** de recuperar el journal de backup y sembrar datos, bajo la misma
barrera exclusiva y **antes** de exponer UI/lectores. Examina únicamente
nombres de fichero UUID canónicos generados por `ExerciseImageManager`;
conserva archivos referenciados y nombres legacy/desconocidos. Para cada
huérfano regular, revalida que Room no tenga referencias, elimina y sincroniza
el directorio. Rechaza raíz enlazada/no directorio, entradas UUID no regulares
y fallos de borrado/fsync. Un error mantiene el arranque cerrado y permite
reintento; la reconciliación es idempotente.

Este mecanismo permite recuperar en el siguiente arranque las imágenes
huérfanas tras un commit Room seguido de muerte de proceso o fallo parcial de
limpieza, sin necesitar una segunda transacción Room/FS. **No demuestra**
durabilidad frente a corte de alimentación ni excluye escrituras de otros
procesos: la exclusión es local al AppContainer. Tampoco elimina ficheros
legacy con nombre no UUID ni temporales `.tmp`, ni garantiza limpieza inmediata
si el proceso continúa después de un error. Los tests Robolectric comprueban
preservación de referencias, reintento tras fallo de borrado y fallo de fsync;
una prueba JVM comprueba orden de startup y gate cerrado hasta reconciliar.
La CI del HEAD debe validarse antes de considerar estas pruebas superadas.

Siguen pendientes auditoría completa de accesos DAO, rotación real/multi-Activity,
durabilidad y cortes físicos Android 15/16. #56 permanece abierta y PR #57
en borrador; no se genera APK candidato ni se fusiona.

## Revisión de rutas de UI y regresiones de reconciliación — 2026-10-29 07:14 UTC

Se ha comprobado el árbol completo de fuentes Kotlin de producción del HEAD `4269afe` (82 archivos bajo `app/src/main/java`, sin truncamiento del árbol) y se han inspeccionado directamente **22 archivos adicionales** de `MainActivity`, `app/`, `feature/`, `domain/usecase/` y `data/export/` buscando construcciones de `GymUpDatabase`, accesos DAO, escrituras directas de archivos, mutaciones Room y DataStore fuera del contenedor. No se detectaron accesos directos a DAO/Room en esas entradas; `AndroidSessionReportFiles` escribe un JSON de informe, no muta el dominio. La inspección es estática y parcial respecto a la totalidad de caminos de ejecución; **no demuestra ausencia de lectores vivos durante multi-Activity ni barrera entre procesos**.

Se añaden dos pruebas Robolectric a `UserImageOrphanReconcilerTest`: (1) conservar nombres legacy y temporales desconocidos mientras se retira un huérfano UUID, y (2) fallar cerrado ante un directorio huérfano con nombre UUID, sin eliminar su contenido. Se aísla la segunda fixture para no contaminar otras pruebas. Commits `44bd8a8` y `f79033d`; **CI del nuevo HEAD pendiente de resultado** al registrar esta sección. La CI anterior [#40828152370](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/40828152370) terminó SUCCESS para `4269afe` (código previo).

Continúan sin verificarse las rutas de lectura fuera de Compose, la rotación y multi-Activity reales, la compatibilidad de fsync de directorios en Android 15/16, la durabilidad Room/DataStore frente a interrupción y los cortes físicos. #56 abierta, PR #57 draft sobre #50; no merge, APK ni release.

## Regresión de enlace simbólico en huérfano UUID — 2026-10-29 08:11 UTC

Dos tests Robolectric de `UserImageOrphanReconcilerTest.kt` (commits
`f37de53` y `a594715`) verifican el rechazo de (1) una entrada UUID
canónica que es un enlace simbólico a un archivo **fuera** de
`exercise-images`, y (2) la propia raíz `exercise-images` enlazada a un
directorio externo. Ambos exigen `IOException` y conservación de los bytes
externos; el segundo aparca y restaura la raíz original de la fixture.
Complementan la prueba de subdirectorio no regular y caracterizan el
comportamiento fail-closed de `NOFOLLOW_LINKS`.

La prueba se ha publicado, pero **requiere Android CI definitiva** antes de
atribuirle éxito. No demuestra que no existan carreras TOCTOU entre la
comprobación y el borrado, ni protección entre procesos. Tampoco acredita
rotación real, durabilidad de fsync en Android 15/16 o cortes de energía.
Se mantiene #56 abierta y PR #57 draft; sin merge ni APK.

## Coordinación de lectores de varias Activity — 2026-10-29 09:xx UTC

Hallazgo: el acuse único de `readersDisposed()` podía iniciar la sustitución
cuando la primera `Activity` había desmontado su UI, aunque otra `Activity`
todavía mantuviera un `NavHost` con lectores Room/DataStore. El contenedor es
compartido por las instancias de `MainActivity`, pero el acuse anterior no
contabilizaba consumidores montados.

La corrección de PR #57 introduce leases por árbol `GymUpReadyApp` en
`HotBackupImportCoordinator`. Cada árbol registra un lector y libera su lease
al desmontarse, **después de limpiar su `ViewModelStore`**. El acuse
`readersDisposed()` ya no libera la importación mientras exista otra lease.
Una regresión JVM crea dos lectores de Activity, retira uno, intenta el acuse
anticipado y comprueba que no se ejecuta la importación hasta retirar el
segundo. También cubre cierre duplicado y nueva generación.

**Límites:** esto solo contabiliza árboles Compose que cooperan con el
coordinador. No prueba carreras de montaje/desmontaje reales, lectores fuera
de Compose, multiproceso, rotación instrumentada, ni durabilidad ante corte
físico. El resultado de Android CI de estos commits debe verificarse antes de
declarar las pruebas superadas. #56 permanece abierta y PR #57 draft.
