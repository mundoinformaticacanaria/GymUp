# ADR-007 (propuesta) — Recuperación duradera de importación replace-all

Estado: **PROPUESTO / IMPLEMENTACIÓN PARCIAL NO CONECTADA**  
Fecha: 2026-10-25  
Issue: #56 · PR: #57  
Relacionado: ADR-005 y contrato funcional v1 §17

## Problema y límite actual

Room, el directorio privado de imágenes y DataStore no comparten una transacción atómica. PR #57 mitiga excepciones y cancelaciones controladas manteniendo abierta la transacción Room y conservando imágenes anteriores. Su CI verde **no demuestra recuperación tras terminación abrupta**: un proceso puede morir después de conmutar imágenes, escribir tema o confirmar Room y antes de limpiar temporales.

El objetivo es garantizar que, al reabrir la app tras una interrupción, el usuario encuentre un estado completo anterior o nuevo, nunca una combinación silenciosa de ambos. Se mantiene ZIP v1, importación completa replace-all, almacenamiento offline y sin servicios externos.

## Avance técnico — 25/10/2026 16:10 UTC

La PR #57 incorpora `BackupRecoveryJournal` y cuatro tests JVM de persistencia entre instancias, corrupción, exclusión de journals pendientes y limpieza posterior a confirmación. El journal escribe una copia previa y marcador versionado con SHA-256, sincroniza los ficheros y falla cerrado ante estado incompleto. **Aún no se utiliza en `RoomBackupDataSource` ni en el arranque; no ofrece recuperación de una importación real tras crash.** La durabilidad de renombres/metadatos de directorio en Android está pendiente de validación, al igual que la coordinación con escrituras concurrentes. La CI del nuevo código debe validarse antes de atribuirle cobertura.

## Avance técnico — 25/10/2026 17:13 UTC

Añadido `BackupRecoveryCoordinator` (PR #57) como máquina de estados aislada, **todavía sin conexión al arranque ni a la importación real**. Al encontrar un journal `PREPARED`, exige ejecutar una restauración inyectada antes de confirmar y limpiar; si la restauración falla o el journal está corrupto, la operación falla cerrada y conserva la copia para un siguiente arranque. `COMMITTED` solo permite limpiar, sin reproducir el backup antiguo. El componente serializa invocaciones dentro de su propia instancia mediante `Mutex`, pero **no garantiza exclusión global** frente a escrituras de otros repositorios ni entre procesos.

Se añadieron cinco tests JVM para journal ausente, restauración correcta, reintento tras fallo, limpieza de `COMMITTED` y rechazo de `PREPARED` corrupto. Estos tests simulan reconstrucción del componente, **no muerte real del proceso ni restauración de Room/imágenes/DataStore**. CI del nuevo HEAD pendiente de verificación. Queda por implementar el adaptador de restauración idempotente y la puerta de arranque antes del seeder y la UI; no considerar #56 resuelta.

## Avance técnico — 25/10/2026 20:10 UTC

Se ha endurecido la limpieza del journal COMMITTED: primero se renombra el directorio
completo a una ruta retirada, después se eliminan sus archivos. Una interrupción durante
la limpieza ya no deja un marcador activo parcialmente eliminado que pueda confundirse
con un PREPARED corrupto. El coordinador reintenta eliminar el directorio retirado al
reconstruirse, y la preparación de otro journal queda bloqueada mientras exista ese
residuo. Se añaden dos tests JVM para interrupción de limpieza y exclusión. **La CI
del nuevo commit está pendiente**.

Esta mitigación no acredita durabilidad del renombrado de directorios tras corte de
alimentación, exclusión global de escrituras, ni conexión con la importación y el arranque.
La recuperación ante muerte real del proceso continúa sin estar garantizada.

## Avance técnico — 25/10/2026 21:12 UTC

Se incorpora `JournaledBackupDataSource` (PR #57, commits `59a9b28` y `4406ef8`), un adaptador **todavía no instalado en AppContainer** que conecta la captura/restauración ZIP v1 con `BackupRecoveryCoordinator.replaceWithRecovery` y expone `recoverBeforeDataAccess`. Tres pruebas JVM cubren reemplazo exitoso, rollback tras mutación fallida y reanudación tras fallo de rollback y reconstrucción del adaptador.

Este paso verifica la composición de los componentes, **no su integración productiva**: aún faltan barrera global de escrituras, captura consistente de Room/imágenes/DataStore, recuperación obligatoria antes del seeder y de la UI, durabilidad de metadatos y pruebas de corte real. No utilizar este adaptador en producción sin esos gates. CI del nuevo HEAD pendiente de resultado definitivo.

## Avance de pruebas — 25/10/2026 22:08 UTC

La PR #57 incorpora en `5db682e` una regresión Robolectric adicional que utiliza
**Room real en memoria, imágenes privadas y DataStore**, en lugar de un delegado falso
como almacén principal. Inyecta un error **después de que el delegado confirme los
tres almacenes**, pero antes de que el coordinador publique `COMMITTED`; comprueba
la restauración de sesión, imagen y tema, la limpieza tras reconstruir el coordinador
y la posibilidad de importar de nuevo. Esta es una **interrupción controlada
inyectada**, no una muerte del proceso ni una prueba de durabilidad en dispositivo.
La CI del commit debe confirmarse antes de declarar superada esta regresión.

**No conectar todavía** `JournaledBackupDataSource` a `AppContainer` ni al
seeder/UI sin exclusión global de escrituras y recuperación obligatoria antes del
primer acceso a datos. La Issue #56 permanece abierta.

## Cobertura incremental — 2026-10-25 23:07 UTC

Se añade en PR #57 la prueba Robolectric `prepared journal before any mutation preserves real stores on restart` (commit `dc938df`): con Room, imágenes privadas y DataStore reales, prepara el journal y reconstruye el coordinador **antes de modificar ningún almacén**. Comprueba que la recuperación no altera el estado previo y elimina el journal tras éxito. Esto cubre un punto adicional de la matriz de interrupciones; **no es una muerte real del proceso**, no demuestra durabilidad ante corte eléctrico ni resuelve la barrera global de escrituras. La CI del commit debe verificarse antes de considerar superada la prueba. No conectar a producción ni cerrar #56.

## Cobertura incremental — 2026-10-26 00:10 UTC

Se incorpora en PR #57 el test Robolectric `committed journal preserves new real stores after simulated restart` (commit `e02f915`). Tras preparar una copia anterior, aplica el reemplazo de Room, imagen privada y tema, confirma el marcador `COMMITTED` y reconstruye el coordinador **antes de limpiar el journal**. Comprueba que el arranque conserva el estado nuevo, no reproduce la copia anterior, limpia el journal y permanece estable en un segundo arranque simulado. **CI pendiente de verificar** para este cambio; no representa una muerte real del proceso ni acredita durabilidad tras corte eléctrico.

Se mantiene el estado de propuesta sin conexión productiva: siguen pendientes exclusión global de escrituras, recuperación obligatoria antes de seeding/UI, validación de persistencia de directorios y prueba instrumentada de interrupción real. No cerrar #56 ni fusionar PR #57.

## Avance técnico — 2026-10-26 01:11 UTC

PR #57 cambia la publicación de `PREPARED`: el archivo de preimagen y el marcador
se escriben y sincronizan primero en un directorio privado `.preparing` y solo
después se renombra el directorio completo al journal activo. Un corte antes de
ese renombrado deja una preparación **no publicada**, que el coordinador elimina
antes de recuperar o iniciar otra importación. Si ya existe un journal activo,
la limpieza del directorio de preparación falla cerrada y no descarta la copia
publicada. Se añaden pruebas JVM para ambos casos. Esta mejora resuelve el
bloqueo permanente causado por una preparación incompleta, pero **no acredita**
la durabilidad del renombrado del directorio tras un corte eléctrico ni
protección frente a procesos/escrituras concurrentes; sigue faltando barrera
global y conexión al arranque/flujo real. CI del nuevo HEAD pendiente.

## Corrección incremental — 2026-10-26 02:08 UTC

Se identificó una ventana adicional en `markCommitted()`: si el proceso muere
después de escribir `state.tmp` y antes de sustituir el marcador `PREPARED`,
el reinicio encuentra correctamente `PREPARED` y restaura la preimagen, pero
el marcador temporal abandonado impedía volver a confirmar, bloqueando la
recuperación de manera persistente.

La corrección descarta **solo** `state.tmp` tras validar el `PREPARED` vigente,
antes de reintentar `COMMITTED`. Un temporal no eliminable provoca fallo
cerrado y conserva la copia anterior. Dos pruebas JVM cubren el reinicio
simulado con temporal abandonado y el caso de fallo cerrado. La CI del nuevo
HEAD requiere comprobación; esto no prueba durabilidad ante corte eléctrico,
exclusión global ni recuperación integrada al arranque.

## Mejora incremental — 2026-10-26 03:11 UTC

`JournaledBackupDataSource.exportSnapshot()` ya no accede al delegado directamente:
usa `BackupRecoveryCoordinator.readSnapshotIfRecovered`, que comparte el mutex del
reemplazo y rechaza la exportación cuando el journal no está `Absent`.
Esto evita leer una instantánea intermedia durante un replace-all ejecutado **a través
de la misma instancia del adaptador** y evita exportar tras una recuperación pendiente.
Se añaden dos pruebas JVM: fallo cerrado ante `PREPARED` y espera de exportación
mientras una importación mantiene el mutex. La CI del nuevo HEAD debe comprobarse.

**Alcance limitado:** no serializa las escrituras realizadas mediante repositorios
Room, gestor de imágenes o DataStore fuera del adaptador, ni distintas instancias
o procesos. Tampoco constituye una barrera de arranque; por tanto no habilita aún
su instalación productiva ni el cierre de #56.

## Protección adicional entre coordinadores — 2026-10-26 04:11 UTC

Los coordinadores que apuntan al mismo directorio canónico de journal ahora comparten
un mutex **dentro del mismo proceso**. Antes, crear dos instancias independientes de
`JournaledBackupDataSource` sobre el mismo directorio permitía que una exportación
o recuperación leyera un journal PREPARED mientras otra instancia estaba sustituyendo
datos. Dos pruebas JVM verifican que la exportación y la recuperación de una segunda
instancia esperan a que finalice la importación en curso.

**Límite explícito:** esta exclusión no cubre escrituras directas desde repositorios
Room, gestores de imágenes, DataStore ni otros procesos; tampoco asegura una captura
coherente ni la recuperación previa a seeding/UI. No se instala todavía el adaptador
en `AppContainer`. CI del nuevo HEAD pendiente de verificación.

## Captura de preimagen — 2026-10-26 05:12 UTC

En PR #57, `RoomBackupDataSource.exportSnapshot()` conserva la transacción de lectura
de Room durante la obtención del tema y la lectura de bytes de imágenes privadas,
en vez de cerrar la transacción antes de leer los archivos. Esto evita que un escritor
de Room modifique las referencias de imágenes mientras se capturan sus bytes.
Una regresión Robolectric con un escritor concurrente verifica que este espera hasta
que termina la captura y que el ZIP refleja el estado anterior coherente de Room.

**No constituye captura global atómica**: `ExerciseImageManager`, DataStore y otros
escritores de archivos pueden cambiar su estado sin tomar la transacción Room.
Tampoco existe recuperación obligatoria antes del seeder/UI, aislamiento entre procesos
ni garantía verificada de durabilidad tras corte eléctrico. El adaptador journaled sigue
sin instalarse en `AppContainer`; PR draft y #56 abierta. La CI del nuevo HEAD debe
verificarse antes de considerar validado este avance.


## Secuenciación del arranque — 2026-10-26 06:13 UTC

Se incorpora `BackupStartupGate` (PR #57) como primitiva aislada para ejecutar
**recuperación antes de seeding** y marcar el arranque listo únicamente cuando
ambas operaciones terminan. Serializa llamantes concurrentes y permite reintentar
un fallo o cancelación sin abrir la puerta. Cuatro pruebas JVM comprueban el orden,
la recuperación fallida, el fallo de seeding y los llamantes concurrentes.

**No se conecta aún al arranque real ni a `AppContainer`/UI**: conectar la puerta
sin proteger previamente todos los escritores de Room, imágenes y DataStore sería
una falsa garantía de seguridad. Tampoco se ha demostrado todavía durabilidad del
journal tras corte de energía ni interrupción real del proceso. CI del HEAD nuevo
pendiente; no instalar el adaptador journaled ni fusionar/cerrar #56.

## Pruebas adicionales del gate — 2026-10-26 07:20 UTC

La PR #57 añade dos regresiones JVM en `BackupStartupGateTest` para cancelación
de corrutinas durante (a) la recuperación y (b) el seeding. Ambas comprueban que
la secuencia interrumpida **no marca ready** y que el siguiente intento repite
la recuperación antes de poder terminar el seeding. Esto verifica la semántica
aislada de la puerta, **no** una recuperación real del proceso ni su integración
en `AppContainer`. CI del nuevo HEAD pendiente de comprobación.

La barrera de escrituras de Room, imágenes y DataStore, la secuenciación real
antes de UI/seeder, durabilidad de metadatos y pruebas físicas siguen pendientes.

## Protocolo técnico propuesto (pendiente de integración y pruebas completas)

1. **Exclusión mutua**: impedir dos importaciones simultáneas y escrituras de dominio concurrentes durante la ventana de reemplazo. Exportar una instantánea consistente del estado previo (Room + imágenes de usuario + tema) y validar sus referencias. Si no puede obtenerse, abortar antes de modificar datos.
2. **Journal privado duradero**: escribir la instantánea anterior en un directorio exclusivo por operación con manifiesto, checksums y versión del protocolo. Crear una marca `PREPARED` de forma atómica **solo después** de persistir y verificar todos los archivos; sincronizar archivos y metadatos de directorio según las garantías reales de Android. Nunca almacenar contenido personal en logs.
3. **Mutación**: tras `PREPARED`, aplicar el reemplazo de Room, imágenes y DataStore bajo la secuencia reversible. Mantener el journal intacto durante toda la operación. La finalización exitosa requiere persistencia confirmada de los tres almacenes.
4. **Punto de confirmación**: retirar/desactivar la marca de recuperación **solo cuando** el nuevo estado completo sea duradero. La limpieza de copias antiguas puede continuar después, de manera idempotente. Si el proceso muere antes del punto de confirmación, la operación se considera no completada y se recupera el estado anterior.
5. **Recuperación de arranque obligatoria**: antes de exponer repositorios/pantallas capaces de leer o escribir datos, comprobar journal pendiente. Si existe, validar la copia anterior y **restaurarla íntegramente**, incluyendo Room, imágenes y tema; después verificar y retirar la marca. Si la recuperación se interrumpe, el journal sigue disponible y el siguiente arranque reintenta. No invocar la ruta normal de importación recursivamente ni eliminar la única copia anterior.
6. **Fallo de recuperación**: bloquear operaciones de dominio que pudieran sobrescribir el estado pendiente; mostrar error recuperable y conservar los archivos. No declarar éxito ni borrar temporales por comodidad.

### Requisitos de diseño antes de implementar

- Verificar si las APIs de escritura/sincronización de Android disponibles permiten el orden de persistencia propuesto; no equiparar `renameTo` por sí solo con durabilidad tras corte de energía.
- Verificar que la captura de Room, imágenes y preferencias sea consistente bajo exclusión de escrituras; documentar el punto de inicio de bloqueo y cómo se libera ante cancelación.
- Evitar usar nombres de carpetas `previous-*` como única fuente de verdad: son ambiguos tras varias operaciones y no prueban si Room confirmó.
- Mantener compatibilidad de journal mediante versión explícita y política de fallo cerrado ante versión desconocida.

## Matriz mínima de pruebas exigida

| Punto de interrupción | Estado esperado al reabrir |
| --- | --- |
| Antes de crear `PREPARED` | Estado anterior intacto; temporales descartables |
| Después de `PREPARED`, antes de modificar Room | Estado anterior íntegro; journal consumido tras recuperación |
| Tras activar imágenes, antes de confirmar Room | Estado anterior íntegro (Room, imágenes y tema) |
| Tras escribir DataStore, antes de confirmar Room | Estado anterior íntegro |
| Tras confirmar Room, antes del punto de confirmación | Estado anterior íntegro (restauración desde journal) |
| Después del punto de confirmación, antes de limpiar temporales | Estado nuevo íntegro; limpieza reintentable |
| Fallo o segunda interrupción durante recuperación | Journal conservado; recuperación idempotente en siguiente arranque |
| Journal incompleto, checksum incorrecto o almacenamiento insuficiente | Fallo cerrado, sin sustitución destructiva ni eliminación de la única copia |

Implementar tests Robolectric con puntos de corte deterministas y reconstrucción del componente (simulación de reinicio); añadir prueba instrumentada/dispositivo para arranque real y restauración física. Una excepción inyectada y capturada dentro del mismo proceso **no simula** una terminación abrupta.

## Gates y alcance

Esta ADR es **propuesta técnica con infraestructura parcial sin conectar**, no evidencia de recuperación real ante crash. No cambiar el contrato de backup, no modificar el APK BF-02 probado y no fusionar PR #57 ni cerrar #56 hasta implementar, verificar CI y resolver la validación física aplicable. La autorización de un candidato APK sigue siendo exclusiva del propietario conforme a #35.

## Cobertura de concurrencia de arranque — 2026-10-26 08:28 UTC

PR #57 incorpora dos pruebas JVM adicionales en `BackupStartupGateTest` (commit `f1ba4d9`): (1) si una recuperación activa falla mientras otra llamada espera, la segunda reintenta la recuperación antes de ejecutar el seeder, sin habilitar el acceso prematuramente; (2) cancelar una llamada que espera no cancela la recuperación activa ni duplica el seeding. Son pruebas de coordinación de la primitiva aislada, **no pruebas de integración con AppContainer/MainActivity ni de muerte real del proceso**. La CI del nuevo HEAD debe verificarse por separado.

Persisten como gates: barrera global para todos los escritores Room/imágenes/DataStore, conexión de recuperación obligatoria antes del seeder y la UI, durabilidad de directorios y pruebas instrumentadas en dispositivo. Mantener PR en borrador e Issue #56 abierta.

## Prueba de composición journal + startup gate — 2026-10-26

La prueba JVM `BackupStartupRecoveryIntegrationTest` (PR #57) utiliza conjuntamente el journal persistido en disco temporal, `BackupRecoveryCoordinator` y `BackupStartupGate`. Comprueba que una preimagen PREPARED se restaura antes del seeder y que el gate no repite la secuencia al estar listo. Comprueba además que un checksum corrupto impide ejecutar el seeder y conserva el journal, y que un reintento posterior a una reparación explícita debe completar recuperación antes de inicializar datos.

Esta es **integración de componentes aislada**, no conexión de producción a `AppContainer`, no barrera global de escrituras y no prueba de muerte real del proceso. El arreglo manual del archivo en el test es solo una simulación controlada: no se ofrece como procedimiento de reparación de usuario. Se mantienen todos los gates anteriores y la PR en draft hasta su resolución.

## Ventana de interrupción tras replay — 2026-10-26 10:10 UTC

Se añade un punto de inyección determinista opcional en `BackupRecoveryCoordinator`
**después** de restaurar la preimagen y **antes** de confirmar el marcador
`COMMITTED` (commits `500d6f6` y `bf7531a`). Una regresión JVM simula
una interrupción en esa ventana y reconstruye el coordinador: el marcador
`PREPARED` debe permanecer íntegro, el siguiente arranque repite el replay
idempotentemente y solo entonces retira el journal. Un arranque posterior
no debe restaurar de nuevo.

Este test cubre una ventana específica de la máquina de estados, **no**
una muerte real de proceso ni una prueba física de durabilidad. La CI del
HEAD actualizado debe verificarse. Siguen sin resolverse barrera global
Room/imágenes/DataStore, integración con `AppContainer`/UI, persistencia
de metadatos de directorio y prueba instrumentada. Mantener PR #57
en borrador e Issue #56 abierta, sin integración productiva.


## Endurecimiento de integridad — 2026-10-26 11:11 UTC

El marcador `COMMITTED` ya no se acepta solo por tener un formato válido:
`BackupRecoveryJournal.inspect()` verifica también que `previous.zip` exista y
que su SHA-256 coincida con el digest registrado antes de permitir retirar el
journal. Antes, un archivo previo ausente o corrupto podía descartarse
silenciosamente durante la limpieza de un `COMMITTED`. Dos tests JVM comprueban
fallo cerrado y preservación de evidencias para ambos casos. El marcador válido
continúa permitiendo limpiar sin restaurar datos anteriores.

**Alcance:** endurecimiento aislado de integridad; no demuestra durabilidad
de directorios, barrera global, integración con `AppContainer` ni corte real
del proceso. La PR #57 permanece draft y la Issue #56 abierta.

## Primer componente de exclusión de escrituras — 2026-10-26 12:16 UTC

Se incorpora `BackupDomainWriteBarrier` (PR #57) como **primitiva aislada**, basada
en un único `Mutex` de corrutinas para serializar operaciones de escritura de
dominio y operaciones exclusivas de backup. Cuatro pruebas JVM verifican espera
de escritor/backup, cancelación del llamante en espera y liberación tras error.

**No está conectada a AppContainer ni a los repositorios.** No garantiza aún
exclusión global: para que sea efectiva, todos los escritores de Room, imágenes
privadas y DataStore deben compartir la misma instancia, y la captura,
publicación de PREPARED, sustitución y recuperación deben ejecutarse dentro
de la sección exclusiva. El código de backup deberá invocar adaptadores de
bajo nivel sin readquirir el mutex (no es reentrante). No protege procesos
externos ni la durabilidad de metadatos. La prueba de CI del HEAD nuevo
debe verificarse antes de atribuir éxito.

Siguientes pasos: inventariar y envolver rutas mutadoras, conectar el
`BackupStartupGate` antes del seeder y de la UI, probar recuperación
instrumentada tras terminación real y verificar las garantías de fsync de
directorios en Android. Mantener #56 abierta y PR #57 draft.

## Composición de barrera y journal — 2026-10-26

La PR #57 añade `BarrierProtectedJournaledBackupDataSource` como envoltorio **aislado**
del adaptador journaled. Mantiene la sección exclusiva de
`BackupDomainWriteBarrier` durante la exportación completa, la importación
(captura anterior, publicación de PREPARED, reemplazo, COMMITTED y limpieza)
y la recuperación previa al acceso a datos. El adaptador interno accede a
almacenes **sin envolver**, para no readquirir un mutex no reentrante.

Tres pruebas JVM comprueban: (1) una exportación espera a un escritor de dominio
y lee su estado terminado; (2) un escritor espera a que finalice una importación
journaled; (3) cancelar una importación que espera al escritor no modifica los
datos ni publica un journal. La CI del nuevo HEAD debe comprobarse.

**Inventario inicial de escritores que deberán adoptar la MISMA barrera antes
de habilitarla en producción (no exhaustivo):**

- `RoomSessionRepository`: creación/edición/borrado de sesiones, ejercicios y
  series, cambios de estado y reordenación.
- `RoomRoutineRepository`: guardar y borrar rutinas.
- `RoomCatalogMaintenanceRepository`: mutación de maestros y ejercicios,
  incluida la eliminación de archivos de imágenes.
- `ExerciseImageManager`: alta y baja de imágenes privadas, con operaciones
  de archivo y DAO que deben quedar en una misma sección.
- `UserPreferencesRepository.setThemeMode`: escritura DataStore, incluida
  la vía desde `AppViewModel`.
- `RoomHistoricalCleanupStore` y `DatabaseSeeder.seedIfNeeded`: limpieza
  histórica y seeding; este último solo debe ejecutarse tras recuperación.
- `RoomBackupDataSource.replaceAll`: escritor especial que deberá ejecutarse
  por la ruta exclusiva, **sin** envoltorios de escritura anidados.

**Gates no satisfechos:** aún no se ha auditado exhaustivamente cada ruta
mutadora ni conectado una única instancia a `AppContainer` y a los repositorios;
el seeder actual arranca asíncronamente y la UI no espera al gate. La barrera
sigue sin ser global ni multiproceso, y la durabilidad de directorios y la
muerte real del proceso continúan sin verificarse. No conectar el wrapper a
producción, fusionar la PR ni cerrar #56 por estas pruebas.

## Adaptador aislado de mutaciones de rutinas — 2026-10-26

La PR #57 incorpora `BarrierProtectedRoutineRepository` como adaptador de composición para `RoutineRepository`. Mantiene `saveRoutine` y `deleteRoutine` bajo la misma `BackupDomainWriteBarrier` durante toda la operación del repositorio (incluida la transacción Room cuando corresponda), y delega las consultas sin modificar su comportamiento. Tres pruebas JVM comprueban que guardar y borrar esperan a un backup exclusivo, y que una excepción libera el mutex.

**Estado: implementado solo en rama, NO instalado en `AppContainer`.** El backup deberá usar siempre el repositorio/almacén **sin envolver**, para evitar bloqueo por adquisición recursiva del mutex no reentrante. Este adaptador no protege las mutaciones directas del DAO, los demás repositorios, archivos ni DataStore. Antes de habilitar la composición productiva siguen siendo obligatorios: inventario completo de escritores, una única instancia de barrera compartida, gate de recuperación antes de seeding/UI, pruebas de integración con los almacenes reales y validación de corte abrupto. No modifica el candidato BF-02, ni satisface por sí solo la Issue #56.

## Adaptador aislado de mantenimiento de catálogos — 2026-10-26

PR #57 incorpora `BarrierProtectedCatalogMaintenanceRepository` (Issue #56) como
otro bloque de composición **sin instalación productiva**. Coordina las seis
mutaciones de `CatalogMaintenanceRepository`: crear/renombrar/desactivar
maestros, crear/editar/eliminar ejercicios. La sección crítica cubre toda la
operación delegada, incluida la posible eliminación de imágenes y referencias
a rutinas al borrar un ejercicio. Las consultas y previsualizaciones siguen
delegadas sin tomar la barrera.

Cuatro pruebas JVM verifican que **cada una de las seis mutaciones** espera
una operación exclusiva de backup, que las lecturas siguen disponibles, que
un fallo del delegado libera el mutex y que cancelar una escritura en espera
no ejecuta la mutación. La CI del HEAD actualizado debe verificarse.

**Límites:** este wrapper no está conectado a `AppContainer`; tampoco cubre
escrituras directas del DAO, `ExerciseImageManager`, favoritos,
`RoomSessionRepository`, `UserPreferencesRepository`, seeding o limpieza.
La ruta interna de backup debe seguir usando dependencias **sin envolver**
para evitar deadlock por adquisición recursiva. Antes de integrar el backup
journaled siguen siendo necesarios inventario completo, una sola barrera
compartida, recuperación antes de seeding/UI, durabilidad de metadatos y
prueba de terminación real. Mantener PR draft y #56 abierta.

## Cobertura incremental de escrituras de sesión — 2026-10-26 16:17 UTC

Se incorpora en PR #57 `BarrierProtectedSessionRepository` (commit `4169d07`),
un adaptador **aislado, todavía no instalado en AppContainer**, que protege con
la misma instancia de `BackupDomainWriteBarrier` las 19 operaciones de mutación
de `SessionRepository`, desde crear/borrar sesiones y editar ejercicios hasta
guardar series y finalizar. Los tres accesos de lectura/observación siguen
delegados sin bloqueo. La prueba JVM (commit `d3ef4c3`) verifica las 19
mutaciones frente a un backup exclusivo, las lecturas, la liberación del mutex
ante excepción y la cancelación de un escritor en espera.

**Esta cobertura no constituye una barrera global de producción**: faltan
escritores de favoritos/catálogos auxiliares, imágenes, preferencias, seeding y
limpieza, así como conectar todos los adaptadores a `AppContainer` con un
único mutex y ejecutar recuperación obligatoria antes de seeding/UI. No debe
pasarse un repositorio protegido a un backup que ya tenga el mutex: no es
reentrante. Tampoco se ha demostrado durabilidad de directorios ni terminación
real del proceso. La CI de estos commits debe verificarse antes de declarar
validación; mantener PR draft e Issue #56 abiertas, sin merge ni APK.

## Cobertura incremental de limpieza histórica — 2026-10-26 17:16 UTC

La PR #57 añade `BarrierProtectedHistoricalCleanupStore` (Issue #56) como
adaptador **aislado y todavía no instalado en AppContainer**. Las operaciones
`deleteTransactionalData` y `compact` toman individualmente la misma
`BackupDomainWriteBarrier` que una operación exclusiva de backup; la
previsualización sigue siendo una lectura no bloqueada. Cuatro tests JVM
comprueban espera de borrado/compactación, lectura concurrente, liberación
ante error y cancelación sin mutación.

El borrado es una transacción Room; la compactación VACUUM posterior no cambia
el contenido lógico. Por ello puede intercalarse una exportación entre ambas
operaciones y reflejar el estado posterior al borrado. Este wrapper no protege
otras llamadas directas a DAO ni convierte la secuencia completa del manager
en una sola sección crítica. Si se revisa esta semántica deberá evaluarse
explícitamente, sin suponer una transacción única entre borrado y VACUUM.

**Gates pendientes:** faltan envolver favoritos, imágenes privadas, DataStore,
seeding y escritores directos; conectar una sola instancia a AppContainer,
instalar la recuperación antes de seeding/UI, demostrar durabilidad de
metadatos y validar terminación real de proceso. La CI del HEAD actualizado
requiere verificación. Mantener PR draft e Issue #56 abierta; sin merge ni APK.

## Adaptador aislado de favoritos — 2026-10-26

PR #57 incorpora `BarrierProtectedExerciseCatalogRepository` (Issue #56), que
coordina la única mutación de `ExerciseCatalogRepository`, `setFavorite`,
mediante `BackupDomainWriteBarrier.withDomainWrite`. Las consultas y los
`Flow` se delegan sin bloquear. Cuatro pruebas JVM comprueban espera ante
backup exclusivo, lecturas durante el bloqueo, liberación tras excepción y
cancelación de un escritor en espera sin llegar al DAO.

**Estado: solo implementado en la rama, NO instalado en `AppContainer`.**
La ruta de importación debe continuar usando los almacenes sin envolver para
evitar bloqueo por mutex no reentrante. Quedan sin proteger escrituras directas
del DAO, `ExerciseImageManager`, `UserPreferencesRepository` (DataStore),
`DatabaseSeeder` y cualquier otra ruta que no pase por adaptadores. Antes de
conectar producción son obligatorias una barrera compartida única, recuperación
previa a seeding/UI, pruebas de integración reales y validación de interrupción
abrupta/durabilidad de metadatos. Este avance no autoriza merge, APK ni cierre
de #56. La CI del nuevo HEAD debe verificarse antes de declararlo validado.

## Adaptador aislado de seeding — 2026-10-26 19:09 UTC

La PR #57 incorpora `BarrierProtectedDatabaseSeeder` (Issue #56) para coordinar
la ejecución completa de `DatabaseSeeder.seedIfNeeded()` con la misma
`BackupDomainWriteBarrier` que la importación y las escrituras de dominio.
Cuatro pruebas JVM verifican que el seeding espera a un backup exclusivo, que
un backup espera a la finalización del seeding, que una cancelación en espera
no ejecuta ninguna escritura y que una excepción libera el mutex.

**No está conectado a `AppContainer` y no sustituye al gate de arranque.**
En producción, `BackupStartupGate` debe terminar la recuperación *antes* de
invocar este seeder protegido y antes de exponer la UI. Un mutex por sí solo
no impone ese orden: si el seeder adquiere primero la barrera podría escribir
antes de detectar un journal PREPARED. Además siguen pendientes las rutas
mutadoras de imágenes, DataStore y DAO directas, el cableado de una única
instancia compartida, durabilidad de directorios y corte real de proceso.
La CI de este nuevo HEAD debe verificarse; mantener #56 abierta y PR draft,
sin merge ni candidato APK.

## Cobertura incremental de DataStore — 2026-10-26 20:12 UTC

Se incorpora `BarrierProtectedThemeModeWriter` como adaptador **aislado**, todavía no
instalado en `AppContainer`, para serializar la mutación de tema de DataStore
(`UserPreferencesRepository.setThemeMode`) con la misma barrera de backup.
Cuatro pruebas JVM cubren espera de la escritura ante backup exclusivo, espera
del backup ante escritura, cancelación del escritor en espera y liberación de
la barrera ante fallo del delegado. Las lecturas de tema no se bloquean.

La conexión futura deberá pasar **todas** las escrituras de tema de UI por
este adaptador, y dejar que backup/recovery invoque el delegado sin envolver
mientras posea la barrera exclusiva: el mutex no es reentrante. La protección
parcial no ofrece consistencia global; siguen pendientes escritores de imágenes,
DAO directos y cableado de `AppContainer`, recuperación obligatoria antes de
seeding/UI, durabilidad de metadatos y prueba de interrupción real. La CI del
HEAD nuevo requiere verificación; PR #57 continúa draft e Issue #56 abierta.

## Cobertura incremental de imágenes — 2026-10-26 21:17 UTC

PR #57 incorpora `BarrierProtectedExerciseImageWriter` (commit `7b2c9e4`), un
adaptador **aislado** para serializar `ExerciseImageManager.addUserImage` y
`deleteUserImage` con la misma `BackupDomainWriteBarrier`. La exclusión cubre
toda la mutación (archivo privado y metadatos Room), no solamente la llamada DAO.
Cinco pruebas JVM (commit `a0db7e3`) verifican espera de alta/borrado ante backup,
espera de backup ante alta, cancelación antes de escribir y liberación tras error.

**No está conectado a `AppContainer`**: la UI sigue recibiendo el gestor de imágenes
directamente, por lo que puede saltarse el adaptador. Instalarlo exige ajustar la
interfaz expuesta a los consumidores y auditar escrituras directas adicionales;
no debe hacerse parcialmente porque crearía una falsa garantía de exclusión.
La recuperación antes de UI/seeding, la durabilidad de metadatos y la prueba de
muerte real del proceso siguen pendientes. La CI del nuevo HEAD debe verificarse;
no se fusiona PR #57 ni se cierra #56 por esta cobertura aislada.

## Composición exclusiva del arranque — 2026-10-26 22:16 UTC

`BackupStartupGate` admite ahora una instancia opcional de `BackupDomainWriteBarrier`
(PR #57, commit `5f83b77`). Si se proporciona, ejecuta **recuperación y seeding
bajo una única sección exclusiva**, evitando que un escritor cooperante se
intercale entre ambas fases. Dos pruebas JVM en `BarrierProtectedBackupStartupGateTest`
(commit `dad50ec`) verifican espera del escritor durante ambas fases y liberación
de la barrera tras fallo de recuperación con reintento posterior.

**Condición de composición:** los callbacks de recuperación y seeding deben usar
los almacenes **sin wrappers de barrera**; llamar a adaptadores ya protegidos
mientras se mantiene el mutex exclusivo produciría interbloqueo. Este gate sigue
**sin instalarse en `AppContainer`** y no impide que lectores o escritores que
ignoren el gate accedan a los datos antes de la recuperación. Tampoco protege
otros procesos, acredita durabilidad de directorios ni sustituye la prueba real
de interrupción. Mantener #56 abierta y PR #57 draft hasta completar los gates.

## Sincronización de bytes de imágenes en staging — 2026-10-26 23:15 UTC

PR #57 incorpora en `RoomBackupDataSource` la escritura de cada imagen importada mediante
`FileOutputStream` y `FileDescriptor.sync()` antes de activar el directorio staged
(commit `c301f08`). Si la escritura o el `sync` falla, el staging se descarta y
la importación no avanza al reemplazo de Room. Los tests Robolectric de round-trip
y rollback existentes cubren el comportamiento lógico, pero **no simulan pérdida
de energía ni demuestran persistencia del directorio**.

**Límite importante:** sincronizar el contenido de cada archivo no hace duraderos
los cambios de nombre de directorios, la creación/eliminación de entradas, la
confirmación de Room o DataStore. Sigue pendiente verificar/implementar la
persistencia de metadatos de directorio en Android 15/16, incluyendo publicación
de PREPARED, transición COMMITTED y activación/retirada de imágenes. El riesgo de
corte abrupto permanece abierto. La PR sigue draft y la Issue #56 abierta; no
instalar en `AppContainer`, fusionar ni generar APK por este avance aislado.

## Cobertura incremental — 2026-10-27 00:12 UTC

PR #57 añade una inyección determinista **después de sincronizar los bytes de una imagen en staging, antes de comenzar la transacción Room** (`RoomBackupDataSource.afterStagedImageSync`). Una prueba Robolectric verifica que, al fallar en ese punto, la sesión anterior, los bytes de la imagen activa y la preferencia de tema permanecen intactos, el staging se retira y el mismo payload puede importarse después sin el fallo. Es una prueba de la frontera de preparación, no una simulación de muerte de proceso.

**Límites:** el callback es únicamente un seam de prueba; no acredita que `fsync` haya persistido las entradas del directorio tras pérdida de energía. Tampoco sustituye la barrera global, la recuperación obligatoria antes de UI/seeder ni la validación física de cortes reales. Verificar CI del nuevo HEAD antes de considerar superada esta regresión. La Issue #56 sigue abierta y PR #57 en borrador.

## Regresión de staging parcial con múltiples imágenes — 2026-10-27 01:12 UTC

La PR #57 añade una prueba Robolectric en `RoomBackupDataSourceTest` que
inyecta un error controlado **tras sincronizar la segunda imagen** de una
importación. Verifica que se descarta el staging parcial (incluida la primera
imagen ya escrita), permanecen intactas ambas imágenes activas, la sesión
previa de Room y la preferencia de tema, y el mismo payload se puede importar
posteriormente sin el fallo. Este caso complementa la prueba anterior de
fallo tras la primera imagen y evita atribuir a un staging parcialmente
completado la semántica de un reemplazo confirmado.

**Alcance limitado:** prueba un fallo recuperable del proceso en ejecución;
no simula muerte abrupta, pérdida de energía ni durabilidad de entradas de
directorio. La recuperación obligatoria antes de UI/seeding, la barrera global
conectada a todos los escritores y la verificación de metadatos de directorio
siguen pendientes. Mantener Issue #56 abierta y PR #57 en borrador.

## Persistencia de imágenes creadas por el usuario — 2026-10-27 02:12 UTC

`ExerciseImageManager.addUserImage` sincroniza ahora los bytes del archivo temporal con
`FileDescriptor.sync()` **antes** de renombrarlo al nombre definitivo y escribir sus
metadatos en Room (commit `7df5532`). Si falla la escritura o sincronización,
la operación aborta sin insertar los metadatos de la imagen; la limpieza del temporal
sigue en `finally`. Esto armoniza la escritura ordinaria de imágenes con el staging
de importación y reduce la ventana de pérdida de bytes frente a interrupciones.

**No constituye durabilidad transaccional**: faltan sincronización/validación de
entradas de directorio, exclusión global de escritores, recuperación productiva antes
de seeding/UI y pruebas instrumentadas de muerte real de proceso en Android 15/16.
La ruta de alta ordinaria continúa sin wrapper de barrera en `AppContainer`.
El cambio debe superar Android CI en su HEAD; no autoriza integrar la PR ni cerrar #56.

## Regresión de alta ordinaria de imágenes — 2026-10-27 03:10 UTC

PR #57 incorpora un punto de fallo determinista opcional en
`ExerciseImageManager.addUserImage`, **después** de escribir y sincronizar
los bytes temporales y **antes** de renombrar el archivo y añadir la fila Room.
Una prueba Robolectric comprueba que el error deja intactos los metadatos de
imágenes del ejercicio, no publica ni abandona archivos nuevos y permite
reintentar el alta con éxito. El callback no cambia el comportamiento de
producción cuando no se inyecta.

**Límites:** esta prueba simula una excepción controlada, no terminación
abrupta ni pérdida de energía. La sincronización de bytes no garantiza
durabilidad de renombres ni entradas de directorio. Tampoco conecta la
barrera global de escrituras ni la recuperación obligatoria en
`AppContainer`. Verificar Android CI del HEAD antes de considerar validado
este caso; mantener Issue #56 abierta y PR #57 en borrador, sin merge,
candidato APK, etiqueta ni release.

## Regresiones de cancelación en coordinator — 2026-10-27 04:14 UTC

La PR #57 añade dos pruebas JVM en `BackupRecoveryCoordinatorTest` para cubrir ventanas
de cancelación que no estaban verificadas directamente en la máquina de estados:

- Cancelación del llamante mientras `applyReplacement` está suspendido, después
  de modificar datos: el rollback debe completarse bajo `NonCancellable`,
  restaurar la preimagen y dejar `COMMITTED` para que un arranque posterior
  limpie el journal sin reproducir el estado antiguo.
- Cancelación durante el replay de `PREPARED` en arranque: el journal y la
  preimagen permanecen intactos; un nuevo coordinador puede repetir el replay
  y confirmar la recuperación sin aceptar el arranque interrumpido.

Estas pruebas no simulan muerte de proceso ni pérdida de energía y no prueban
durabilidad de directorios. Tampoco instalan recuperación/barrera en
`AppContainer`. Debe verificarse Android CI para el HEAD que las incluye;
mantener #56 abierta y PR #57 draft, sin merge ni APK.

## Sincronización de metadatos del journal — 2026-10-27 05:09 UTC

PR #57 añade sincronización explícita de **directorios** alrededor de las
operaciones críticas de `BackupRecoveryJournal` (commit `00cb2cd`).
Tras crear `.preparing`, publicar `previous.zip` y `state`, renombrar el
directorio a `PREPARED`, publicar el marcador `COMMITTED` y retirar el
journal confirmado, se fuerza la persistencia de las entradas de directorio
mediante `FileChannel.force(true)` sobre el directorio correspondiente.
También se sincroniza el padre tras limpiar directorios retirados o
preparaciones abandonadas. Si una sincronización falla, se **propaga el
error**: no se declara preparación o confirmación duradera por defecto.

Tres pruebas JVM (commit `41f2612`) comprueban el orden de sincronización,
la conservación de la preimagen cuando falla el sync del directorio PREPARED
y el error explícito al fallar el sync tras el rename de COMMITTED. Una cuarta
regresión (commit `4fb9ed9`) verifica que, si falla la sincronización del
PREPARED publicado, el coordinador **no inicia la mutación** y una nueva
instancia puede recuperar la preimagen antes de continuar.

**Límite crítico:** esto es una mitigación técnica, **no una prueba de
durabilidad real en Android 15/16**. Debe verificarse en dispositivo que el
proveedor NIO permite abrir y sincronizar directorios privados, y comprobar
cortes reales, persistencia de Room/DataStore y activación de imágenes.
Un fallo después de renombrar COMMITTED pero antes de sincronizar el
directorio puede dejar COMMITTED observable durante la ejecución, aunque
su persistencia tras pérdida de energía siga siendo incierta. El código
debe superar Android CI del HEAD; la PR permanece draft y #56 abierta.
La barrera global y el gate productivo en AppContainer siguen pendientes.

## Prueba instrumentada de compatibilidad de fsync — 2026-10-27 06:15 UTC

Se añade `BackupRecoveryJournalDeviceTest` en `app/src/androidTest` para
comprobar en un **dispositivo/emulador Android real**, con el almacenamiento
privado de la aplicación, las llamadas a `FileChannel.force(true)` que
sincronizan el directorio padre y las entradas de PREPARED, COMMITTED y
retirada del journal. Una segunda prueba cubre la limpieza de una
preparación abandonada y su posterior reutilización.

La prueba tiene dependencias de instrumentación separadas del conjunto
JVM/Robolectric. **No se ha ejecutado en dispositivo** en esta pasada;
el workflow ordinario de Android CI no ejecuta `connectedDebugAndroidTest`.
La validación pendiente debe realizarse en Android 15 y 16, con
evidencias de dispositivo, API, sistema de archivos, resultado y commit.
No se inicia ejecución que requiera generar o instalar APK sin la
autorización aplicable del propietario.

Incluso si ambas pruebas pasan, solo acreditan que la API y el sistema
de archivos **aceptan** el protocolo de sincronización en el entorno
ensayado: no prueban supervivencia a corte eléctrico, recuperación tras
muerte real de proceso, persistencia de Room/DataStore, barrera global
ni conexión de recuperación previa a UI/seeding. Issue #56 y PR #57
siguen abiertas/draft, sin merge ni candidato.

## Protección ante confirmación de journal ambigua — 2026-10-27 07:12 UTC

En PR #57 se detectó una ventana de integridad en `BackupRecoveryCoordinator.replaceWithRecovery`:
`markCommitted()` puede publicar el marcador `COMMITTED` y fallar al sincronizar
el directorio. El `catch` anterior intentaba restaurar el estado previo y
confirmarlo de nuevo; como el marcador ya era `COMMITTED`, esa segunda
confirmación fallaba. Una interrupción durante la restauración podía dejar
un dominio parcialmente antiguo con `COMMITTED`, que al arrancar **no**
reproduce la preimagen.

La corrección `d6ece26` solo permite rollback si el journal todavía puede
inspeccionarse inequívocamente como `PREPARED`. Si el marcador ya es
`COMMITTED` o no se puede verificar, conserva los almacenes sin intentar una
restauración destructiva y **propaga el error original**. Dos regresiones JVM
(`50ad783`) cubren el fallo de sincronización después del rename de
`COMMITTED` y un marcador corrupto después de modificar el dominio.
En el segundo caso el arranque sigue bloqueado hasta recuperar el journal.

**Limitación importante:** ante un fallo de sincronización con `COMMITTED`
visible, la importación puede comunicar error aunque los datos nuevos ya
estén aplicados. La durabilidad del marcador y de los almacenes ante pérdida
de energía continúa sin demostrarse; este guard evita una restauración
peligrosa, pero **no resuelve la ambigüedad de confirmación** ni habilita
integración productiva. La semántica final de éxito/error requiere diseñar
y verificar un protocolo de confirmación duradero. Verificar CI del nuevo
HEAD antes de atribuir cobertura. Mantener Issue #56 abierta y PR #57 draft,
sin merge, APK, etiqueta ni release.

## Regresión de confirmación ambigua durante recuperación — 2026-10-27 08:06 UTC

La prueba JVM `startup replay with ambiguous committed sync never replays old data twice`
(commit `db20188`) cubre una ventana diferente a la importación: el replay de
un journal `PREPARED` termina de restaurar la preimagen, publica `COMMITTED`,
pero falla la sincronización de la entrada del directorio. La primera recuperación
propaga el error y no permite considerar listo el arranque; una instancia nueva
observa `COMMITTED`, limpia el journal y **no vuelve a aplicar la preimagen**.

Es una simulación de fallo de sincronización sobre el sistema de archivos de la
JVM, no una prueba de persistencia tras pérdida de energía. No modifica la
semántica de producción ni resuelve la ambigüedad física de `fsync`.
La barrera de escritores y el gate de recuperación aún no están conectados
a `AppContainer`. Requiere CI del HEAD y pruebas Android 15/16 antes de
cualquier integración; #56 y PR #57 siguen abiertas/draft.
