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
