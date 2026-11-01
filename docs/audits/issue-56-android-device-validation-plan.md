# #56 — Plan de validación Android 15/16 (pendiente de ejecución)

Estado: **protocolo, no evidencia de pruebas superadas**. Rama `fix/issue-56-backup-rollback`, PR #57. No altera el candidato BF-02 ni autoriza un APK candidato/release.

## Precaución de aislamiento

**Ejecutar exclusivamente en un emulador AVD desechable o dispositivo de pruebas sin datos reales de GymUp.** `connectedDebugAndroidTest` instala la APK debug y la APK de instrumentación. `HotBackupImportActivityDeviceTest` lanza `MainActivity` de la **aplicación bajo prueba** (no una aplicación de pruebas con base de datos separada), y el arranque puede crear/sembrar su Room y preferencias. Aunque la importación de estas pruebas usa un ZIP inválido para impedir un reemplazo, eso **no** garantiza ausencia de escrituras de inicialización. **Los dos tests `HotBackupImportActivityDeviceTest` fallan antes de abrir `MainActivity` si no se pasa el opt-in `gymup_disposable_test_device=true`.** Es una barrera contra ejecuciones accidentales de la Activity, no verifica que el dispositivo realmente esté vacío. **La instrumentación puede arrancar el proceso objetivo y ejecutar `GymUpApplication.onCreate()` antes de evaluar el opt-in; por ello esta barrera NO garantiza que no haya inicialización previa ni sustituye el aislamiento del dispositivo.** No ejecutar sobre la instalación personal ni desinstalar/borrar datos de un dispositivo con información real.

Los tests de `UserImageOrphanReconcilerDeviceTest` emplean un `filesDir` temporal y Room en memoria; los de `BackupRecoveryJournalDeviceTest` usan una carpeta aleatoria bajo `noBackupFilesDir`. Esta separación **no** se debe extrapolar a las pruebas de `MainActivity`.

## Preparación

1. Usar checkout exacto del HEAD de PR #57 y registrar su SHA antes de ejecutar. Requiere JDK 17, Android SDK y Gradle del proyecto.
2. Preparar **dos entornos desechables independientes**, Android 15/API 35 y Android 16/API 36. Verificar la versión real con `adb -s <SERIAL> shell getprop ro.build.version.sdk`; no inferirla del nombre del AVD.
3. Mantener un único dispositivo de pruebas conectado/seleccionado por ejecución. No conectar el teléfono personal. Conservar log de comando, dispositivo/API, SHA, resultado y fallos. Evitar subir a GitHub volcados que puedan contener datos personales.
4. **Solo tras verificar el dispositivo/AVD desechable**, ejecutar desde el repositorio: `gradle --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.gymup_disposable_test_device=true`. Sin ese argumento los tests que abren `MainActivity` **fallan deliberadamente antes del lanzamiento**; un fallo por falta de opt-in no es una regresión de producto. El argumento no acredita aislamiento: conservar el serial/API real en la evidencia. El CI ordinario **solo compila** `compileDebugAndroidTestKotlin` y no ejecuta estos tests. Si la infraestructura local no permite Gradle o ADB, registrar el bloqueo; no marcar ninguna prueba como superada.

## Matriz de aceptación

| Caso | Clase instrumentada | Qué comprobar | Estado |
| --- | --- | --- | --- |
| fsync de PREPARED/COMMITTED y limpieza | `BackupRecoveryJournalDeviceTest` | Metadatos y reapertura en filesystem real | Pendiente API 35/36 |
| PREPARED tras fallo y reintento | `BackupRecoveryJournalDeviceTest` | Preimagen preservada y replay idempotente tras reconstrucción de objetos | Pendiente API 35/36 |
| Huérfanos, referencias compartidas y última referencia | `UserImageOrphanReconcilerDeviceTest` | No borrar archivos referenciados; retirar solo huérfanos; segundo pase inocuo | Pendiente API 35/36 |
| Enlaces simbólicos | `UserImageOrphanReconcilerDeviceTest` | Fallo cerrado sin leer/borrar el destino externo | Pendiente API 35/36 |
| Dos Activity y Activity detenida | `HotBackupImportActivityDeviceTest` | No iniciar reemplazo mientras exista lease de lector | Pendiente API 35/36 |
| Recreación durante ClosingReaders | `HotBackupImportActivityDeviceTest` | Rotación/recreación no libera lease ajeno | Pendiente API 35/36 |

## Pruebas que **no** quedan cubiertas por esta batería

- Muerte real del proceso o corte de alimentación entre commit de Room, DataStore, renames y fsync; la reconstrucción de objetos y la inyección de fallos **no equivalen** a estos cortes.
- Persistencia durable de Room (incluido WAL), DataStore y entradas de directorio bajo interrupción real, ni exclusión entre procesos independientes.
- Auditoría exhaustiva de accesos DAO/lectores fuera de adaptadores; compatibilidad de multi-window y todos los ciclos de vida posibles.
- Regresión funcional física BF-02, accesibilidad, fuente ampliada y backup/restore con datos de prueba representativos.

**Puerta de integración:** conservar #56 abierta y PR #57 en DRAFT hasta registrar resultados por API, evidencias de cortes reales y auditoría final; no inferir aceptación del MVP ni generar candidato desde este protocolo.

## Identidad de directorios regulares en Android (01/11/2026)

La regresión instrumentada `BackupRecoveryJournalDeviceTest.swappedRegularPreparingRootIsRejectedByDeviceFileIdentity` (commit `8082034`) verifica sobre el filesystem privado real que `BasicFileAttributes.fileKey()` no es nulo para la raíz `recovery.preparing`, y que la limpieza rechaza un intercambio determinista por otro directorio **regular** tras el preflight. Debe conservar intactas ambas preimágenes. Los datos usados son bytes ficticios en rutas temporales bajo `noBackupFilesDir`; no accede a Room, preferencias ni imágenes personales.

**Estado: prueba publicada, NO ejecutada en Android.** CI ordinaria solo compila `androidTest`; comprobar resultado CI del HEAD. Ejecutar sobre AVD desechables API 35 y 36 según el protocolo anterior, registrar SHA, API, serial y resultado. Un `fileKey()` nulo debe producir fallo explícito: la protección actual es best-effort y no puede darse por acreditada en ese filesystem. Un resultado verde tampoco prueba atomicidad ante sustituciones concurrentes, muerte de proceso o cortes.

## Identidad de ficheros regulares durante limpieza (01/11/2026)

La regresión instrumentada `BackupRecoveryJournalDeviceTest.swappedRegularArchiveIsRejectedByDeviceFileIdentity` (commit `376de8e`) complementa el caso anterior: prepara `recovery.preparing/previous.zip` en una carpeta temporal, comprueba que el filesystem Android expone un `BasicFileAttributes.fileKey()` no nulo para el **archivo regular**, y lo sustituye de forma determinista por otro archivo regular justo después del preflight de limpieza. La operación debe fallar sin borrar ni modificar ninguno de los dos archivos. Solo usa bytes ficticios bajo `noBackupFilesDir`, sin datos de GymUp del usuario.

**Estado: publicada, no ejecutada en Android.** La CI ordinaria únicamente compila la prueba; se requieren AVD desechables API 35 y 36 con registro del SHA, serial y resultado. La identidad no nula y el rechazo en este fixture no garantizan protección atómica frente a carreras entre comprobación y borrado, ni durabilidad ante cortes o muerte real de proceso.

## Sustitución de raíz COMMITTED por otro directorio válido (01/11/2026)

La prueba instrumentada `BackupRecoveryJournalDeviceTest.swappedCommittedRootKeepsBothPreimagesOnDevice` (commit `af591a8`) construye dos journals **COMMITTED válidos** con preimágenes diferentes en carpetas aleatorias de `noBackupFilesDir`. Inyecta una sustitución de la raíz activa por el segundo directorio justo después de inspeccionar el primero y antes de retirarlo. La limpieza debe **rechazar** el intercambio y preservar los bytes y marcadores de ambas copias. A diferencia de una prueba de symlink, usa dos directorios regulares y `renameTo` real en el filesystem Android.

**Estado: fuente publicada; compilación y ejecución Android 15/16 pendientes.** El CI ordinario solo compila `androidTest`, no ejecuta el caso en AVD. Un resultado verde en este intercambio determinista no demuestra protección atómica frente a TOCTOU concurrente, identidad persistente ante reciclaje de inodos, fsync duradero tras corte, ni recuperación de Room/DataStore. No conectar el limpiador ni fusionar PR #57 por este resultado.

## COMMITTED con preimagen idéntica y raíz distinta (01/11/2026)

La inspección de `cleanupCommitted()` comprobaba el estado y los bytes del marcador tras sustituir la raíz. Dos directorios COMMITTED distintos con **la misma preimagen** superaban esas comprobaciones y podían retirar el journal equivocado. La corrección añade comparación `BasicFileAttributes.fileKey()` de la raíz entre la inspección y el `renameTo`, cuando el filesystem ofrece identidad. La regresión JVM y `BackupRecoveryJournalDeviceTest.swappedCommittedRootWithIdenticalPreimageIsRejectedByDeviceIdentity` prueban el caso de bytes y marcadores idénticos.

**Estado:** fuente preparada; comprobar CI exacta y ejecutar Android 15/API35 y Android 16/API36 sobre AVD desechables antes de afirmar compatibilidad. La defensa sigue siendo best-effort si `fileKey()` es nulo y no es atómica ante TOCTOU, reciclaje de inodos ni cortes reales. No se autoriza limpiar `exercise-images-previous-*`, activar el limpiador aislado, fusionar #57 ni generar APK.
