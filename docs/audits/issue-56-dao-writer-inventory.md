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
