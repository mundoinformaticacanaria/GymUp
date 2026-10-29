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
