# #56 — Auditoría del gate de UI durante importación en caliente

Estado: **pendiente de implementación**. Revisión estática del 2026-10-29.
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

No hay exclusión de lectores/flows durante importación en caliente, limpieza recuperable de imágenes tras baja de ejercicio, auditoría completa de accesos directos DAO, garantía de durabilidad física ni prueba de corte real. #56 y PR #57 deben permanecer abiertas/en borrador. No generar APK ni fusionar por esta evidencia.

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
