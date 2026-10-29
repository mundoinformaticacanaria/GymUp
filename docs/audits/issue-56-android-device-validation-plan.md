# #56 — Plan de validación Android 15/16 (pendiente de ejecución)

Estado: **protocolo, no evidencia de pruebas superadas**. Rama `fix/issue-56-backup-rollback`, PR #57. No altera el candidato BF-02 ni autoriza un APK candidato/release.

## Precaución de aislamiento

**Ejecutar exclusivamente en un emulador AVD desechable o dispositivo de pruebas sin datos reales de GymUp.** `connectedDebugAndroidTest` instala la APK debug y la APK de instrumentación. `HotBackupImportActivityDeviceTest` lanza `MainActivity` de la **aplicación bajo prueba** (no una aplicación de pruebas con base de datos separada), y el arranque puede crear/sembrar su Room y preferencias. Aunque la importación de estas pruebas usa un ZIP inválido para impedir un reemplazo, eso **no** garantiza ausencia de escrituras de inicialización. No ejecutar sobre la instalación personal ni desinstalar/borrar datos de un dispositivo con información real.

Los tests de `UserImageOrphanReconcilerDeviceTest` emplean un `filesDir` temporal y Room en memoria; los de `BackupRecoveryJournalDeviceTest` usan una carpeta aleatoria bajo `noBackupFilesDir`. Esta separación **no** se debe extrapolar a las pruebas de `MainActivity`.

## Preparación

1. Usar checkout exacto del HEAD de PR #57 y registrar su SHA antes de ejecutar. Requiere JDK 17, Android SDK y Gradle del proyecto.
2. Preparar **dos entornos desechables independientes**, Android 15/API 35 y Android 16/API 36. Verificar la versión real con `adb -s <SERIAL> shell getprop ro.build.version.sdk`; no inferirla del nombre del AVD.
3. Mantener un único dispositivo de pruebas conectado/seleccionado por ejecución. No conectar el teléfono personal. Conservar log de comando, dispositivo/API, SHA, resultado y fallos. Evitar subir a GitHub volcados que puedan contener datos personales.
4. Desde el repositorio: `gradle --no-daemon :app:connectedDebugAndroidTest`. El CI ordinario **solo compila** `compileDebugAndroidTestKotlin` y no ejecuta estos tests. Si la infraestructura local no permite Gradle o ADB, registrar el bloqueo; no marcar ninguna prueba como superada.

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
