# ADR-007 (propuesta) — Recuperación duradera de importación replace-all

## Gate de UI para importación en caliente — 2026-10-29 03:14 UTC

La PR #57 conecta `HotBackupImportCoordinator` a `DefaultAppContainer` con
`SupervisorJob` de aplicación y deriva la confirmación de `BackupSection`
a esa operación, sin depender del scope de pantalla. `GymUpApp` retira
`GymUpReadyApp` durante `ClosingReaders` y confirma el cierre mediante
`SideEffect` tras aplicar la eliminación del árbol Compose. Un
`ViewModelStore` propio del árbol se limpia al desmontarlo para cancelar
también el lector persistente de preferencias de `AppViewModel`. La UI
permanece cerrada durante importación, recuperación y fallo bloqueante; tras
resultado terminal se crea una nueva generación de navegación/ViewModels y se
presenta el resultado al usuario. Se añaden pruebas JVM del segundo handshake
y Robolectric del cableado real antes de importar.

**Límites:** aún falta verificar CI del nuevo HEAD, rotación/recreación de
Activity y cancelación de lectores en instrumentación, así como auditar
accesos DAO directos o fuera de composición. No se ha probado durabilidad
ante corte de proceso/energía ni limpieza recuperable de imágenes tras bajas.
La Issue #56 sigue abierta y PR #57 en borrador; sin merge ni APK.


## Auditoría de escritores y límites de visibilidad — 2026-10-28 22:08 UTC

La inspección de `DefaultAppContainer` confirma que los métodos mutadores
de `SessionRepository`, `RoutineRepository` y `CatalogMaintenanceRepository`
están sobreescritos por adaptadores de barrera; los delegados restantes son
consultas/flows. También están protegidos el favorito del catálogo,
`ExerciseImageManager`, `UserPreferencesRepository.setThemeMode` y la
limpieza histórica. Recuperación y seeding comparten una sección exclusiva
en `BackupStartupGate`. El backup y el seeder emplean delegados crudos
dentro de esa exclusión para no reacquirir el mutex no reentrante.
`RoomHistoryRepository` solo consulta.

Se incorpora una regresión Robolectric de composición real que exporta el
tema, lo modifica y lo restaura mediante importación completa. El resultado
de CI del nuevo HEAD debe verificarse antes de atribuirle validación.

**Pendiente crítico:** este inventario no acredita todas las llamadas DAO
ni protege lecturas/flows durante una importación iniciada con la app abierta.
`BackupStartupGate.ready` solo gobierna el arranque inicial: si una
importación en caliente deja `PREPARED` por rollback fallido, la UI ya
compuesta no se cierra automáticamente. Además,
`RoomCatalogMaintenanceRepository.deleteExercise` confirma Room antes de
borrar imágenes USER; la barrera serializa escritores pero no recupera una
caída entre fases. Deben resolverse visibilidad durante fallo en caliente,
limpieza recuperable, accesos directos, durabilidad y pruebas físicas de
interrupción/reinicio Android 15/16 antes de cerrar #56. La PR sigue draft,
sin merge ni candidato APK.

Estado: **IMPLEMENTACIÓN EXPERIMENTAL CONECTADA EN PR #57 (DRAFT) — SIN VALIDACIÓN FÍSICA NI CIERRE**  
Fecha: 2026-10-25  
Issue: #56 · PR: #57  
Relacionado: ADR-005 y contrato funcional v1 §17

## Limpieza plana de directorios de imágenes — 2026-10-28 20:10 UTC

En `StagedImageReplacement.rollback` y `complete`, la retirada de
directorios importados/anteriores deja de utilizar `File.deleteRecursively()`.
La función `deleteFlatImageDirectoryNoFollow` acepta exclusivamente una
raíz de directorio real y entradas planas regulares o enlaces simbólicos;
elimina cada enlace **como enlace**, sin recorrer su destino, y rechaza
subdirectorios inesperados o una raíz enlazada. El rollback exige limpieza
correcta antes de restaurar el directorio anterior; si falla, deja el
journal PREPARED para reintento. La limpieza de una importación ya confirmada
sigue siendo best-effort y no convierte un commit exitoso en un falso fallo.

Cuatro pruebas JVM cubren directorio plano, enlace interno a directorio
externo, raíz enlazada y subdirectorio inesperado. **La CI de estos commits
debe verificarse**. La operación sigue sin ser atómica frente a sustituciones
concurrentes de rutas/ancestros y no resuelve por sí sola residuos de imágenes
de una caída abrupta; faltan auditoría de escritores/DAO, durabilidad real y
pruebas de corte Android 15/16.

## Auditoría parcial de composición productiva — 2026-10-28 20:10 UTC

Revisión estática de `DefaultAppContainer`: los repositorios de sesiones,
rutinas, catálogo de ejercicios, mantenimiento de catálogos y limpieza
histórica se exponen mediante adaptadores con la misma barrera. El gestor de
imágenes y el repositorio de preferencias reciben esa barrera; el delegado
crudo de backup no la recibe para evitar bloqueo por mutex no reentrante.
`BackupStartupGate` mantiene la exclusión durante recovery y seeding,
y `GymUpApp` no compone ViewModels antes de `awaitReady()`.

**Esta revisión no es una certificación global**: aún hay que inventariar
cada escritura DAO/archivo/DataStore y comprobar accesos fuera del contenedor,
concurrencia y terminación abrupta. Algunos comentarios históricos en
adaptadores aún describen su etapa anterior sin conexión; el cableado
efectivo se determina por el código de `AppContainer`, no por esos comentarios.

## Integración experimental de recuperación y puerta de UI — 2026-10-28 19:07 UTC

`DefaultAppContainer` compone `JournaledBackupDataSource` con el mismo
`BackupDomainWriteBarrier` usado por repositorios, imágenes y preferencias.
`BackupStartupGate` ejecuta recuperación y seeding en una única sección
exclusiva, y `GymUpApp` no compone ningún consumidor de repositorios ni
ViewModel hasta que `awaitReady()` termina. Ante fallo de recuperación,
muestra un estado cerrado con reintento, sin presentar datos potencialmente
inconsistentes. Se elimina el seeding independiente lanzado en segundo plano.

Se añade una prueba de integración Robolectric de inicialización, exportación
e importación usando la composición productiva. **Esto no acredita durabilidad
física**: faltan la auditoría final de escritores/DAO que puedan saltarse la
barrera, pruebas de corte real y persistencia de metadatos en Android 15/16,
incluidas ventanas de limpieza de imágenes. La PR permanece draft y #56
abierta, sin autorización para fusionar ni generar un candidato APK.

## Verificación de contenido del staging tras fsync — 2026-10-28 18:13 UTC

La comprobación anterior del staging validaba únicamente que cada imagen seguía siendo
un fichero regular y tenía la longitud esperada. Un archivo regular sustituido por
otro de **igual tamaño pero contenido distinto** podía superar esa validación.
Ahora se abre el descriptor con `NOFOLLOW_LINKS`, se comprueba su tamaño y se
compara el contenido leído con los bytes originales del ZIP antes de publicar
el directorio o modificar Room y DataStore. Una prueba Robolectric inyecta
corrupción de igual longitud después del fsync y exige fallo cerrado, preservación
de imagen activa, referencias Room y tema, y limpieza del staging.

**Límites:** verificar bytes no evita modificaciones concurrentes posteriores a
la lectura ni carreras en directorios antecesores. No sustituye un journal
conectado a producción, el gate de recuperación previo a UI/seeding, la auditoría
de escritores/DAO ni las pruebas físicas de corte Android 15/16. CI del nuevo
HEAD pendiente de comprobación; #56 abierta y PR #57 draft.

## Revalidación del staging tras fsync y limpieza sin seguir enlaces — 2026-10-28 17:08 UTC

`UserExerciseImageStore.stageReplacement` vuelve a abrir cada imagen staged con
`NOFOLLOW_LINKS` después del seam de sincronización y comprueba que sigue siendo
regular y conserva el tamaño esperado. Si la ruta fue sustituida por un symlink,
la importación aborta antes de cambiar Room o preferencias. La limpieza de un
staging fallido elimina exclusivamente entradas planas mediante `Files.delete`,
sin recorrer enlaces ni subdirectorios inesperados; si no puede limpiarse,
conserva el error original y adjunta el fallo de limpieza.

Una regresión Robolectric inyecta el symlink **después del fsync**, verifica que
los bytes externos, Room, la imagen activa y el tema permanecen intactos y que
el staging no persiste. **Límite:** la revalidación sigue teniendo una ventana
TOCTOU hasta el rename, y la limpieza no es una operación atómica frente a
actores que modifiquen el directorio. No se ha instalado el journal productivo,
no hay recuperación antes de UI/seeding ni pruebas físicas de corte Android 15/16.
La CI del nuevo HEAD debe comprobarse antes de atribuirle validación.

## Preparación de imágenes de backup sin sobrescritura externa — 2026-10-28 16:09 UTC

`UserExerciseImageStore.stageReplacement` deja de abrir imágenes de staging con
`FileOutputStream` truncante. Ahora rechaza bytes vacíos o mayores de 10 MiB,
crea cada archivo con `CREATE_NEW + NOFOLLOW_LINKS` y sincroniza su descriptor
antes de publicar el directorio. Una sustitución por symlink justo antes de
abrir falla sin escribir en el destino externo. Dos regresiones Robolectric
cubren la inyección de symlink y el exceso de tamaño, verificando que Room,
imágenes previas y tema permanecen intactos y no queda staging abandonado.

**Límites:** no es una transacción entre Room, imágenes y DataStore; no
elimina carreras de sustitución en directorios antecesores ni acredita
durabilidad física tras muerte del proceso. La CI del HEAD debe verificarse.
El journal sigue sin instalarse en `AppContainer` ni en el arranque. #56
permanece abierta y PR #57 en borrador.

## Escritura de imágenes USER sin seguir temporales enlazados — 2026-10-28 15:12 UTC

`ExerciseImageManager.addUserImage` deja de abrir el temporal mediante
`FileOutputStream` (que podría truncar un destino externo si se sustituye
la ruta por un symlink) y utiliza `FileChannel.open(CREATE_NEW, WRITE,
NOFOLLOW_LINKS)`, con `force(true)` antes de publicar el nombre. Tras el
seam de sincronización vuelve a comprobar que el temporal es un archivo
regular sin seguir enlaces, antes del rename y antes de insertar en Room.

Dos regresiones Robolectric inyectan enlaces simbólicos antes de la apertura
y después de la sincronización, verificando que los bytes externos permanecen
intactos y que no se crea una referencia Room. La CI del HEAD debe completarse
antes de atribuir validación a estos cambios.

**Límites:** la segunda comprobación no es una operación atómica con el
rename; persisten TOCTOU sobre el temporal y los directorios antecesores.
No sustituye la limpieza recuperable de imágenes, la instalación productiva
del journal, la barrera completa ni las pruebas reales de corte Android 15/16.

## Publicación segura de archivos temporales del journal — 2026-10-28 14:09 UTC

`BackupRecoveryJournal.writeSynced` crea `previous.zip.tmp` y `state.tmp`
mediante `FileChannel.open(CREATE_NEW, WRITE, NOFOLLOW_LINKS)`, en lugar de
`FileOutputStream` tras una comprobación separada. Así se evita que un enlace
simbólico inyectado entre el preflight y la apertura redirija la escritura
o trunque un fichero externo. Se conserva `force(true)` sobre el descriptor
antes de publicar el archivo por rename y sincronizar su directorio.

Dos pruebas JVM inyectan enlaces simbólicos exactamente antes de la apertura:
durante la preparación no se publica PREPARED, y durante la confirmación
permanece PREPARED con la preimagen verificable. En ambos casos se conservan
los bytes del destino externo. La CI de este HEAD debe verificarse antes
de afirmar que las regresiones han superado el pipeline.

**Límites:** no elimina carreras sobre directorios antecesores, no verifica
durabilidad ante corte eléctrico ni instala el journal en el flujo real de
backup/arranque. Siguen pendientes la barrera integral, la recuperación
antes de UI/seeding y las pruebas reales Android 15/16. #56 continúa
abierta y PR #57 en borrador.

## Lectura de journal protegida frente a sustitución del archivo final — 2026-10-28 13:15 UTC

La inspección de `state` y `previous.zip` abre ahora los descriptores con
`NOFOLLOW_LINKS`, tras un preflight y antes de leer bytes. Se verifica el tamaño
del descriptor abierto y se limita la lectura al tamaño esperado más un byte;
si cambia durante la lectura, se rechaza el journal. Dos pruebas JVM inyectan
una sustitución por symlink **entre el preflight y la apertura** para ambos
archivos y comprueban fallo cerrado y conservación de los originales y de los
destinos externos. La CI de estos cambios debe verificarse antes de atribuir
las pruebas como superadas.

**Límites:** el descriptor protegido evita seguir enlaces simbólicos en el
componente final, pero no resuelve carreras de sustitución en directorios
antecesores, procesos externos, durabilidad del sistema de archivos ni los
gates pendientes de integración productiva del journal y recuperación antes
de UI/seeding. #56 sigue abierta y PR #57 permanece en draft.

## Protección de raíz de imágenes en baja de ejercicio — 2026-10-28 11:08 UTC

La baja definitiva de un ejercicio valida que la raíz privada `exercise-images`
no sea un enlace simbólico (incluido colgante) ni un archivo ordinario, además
de validar cada referencia `user:` y el tipo del fichero final. Se comprueba
antes de confirmar Room y de nuevo antes de cada eliminación física. Esto
impide que una raíz ya enlazada redirija el borrado a un directorio externo;
las regresiones Robolectric verifican la conservación de ejercicio, referencias
y bytes externos para una raíz enlazada y la preservación de Room ante raíz
colgante. La CI del nuevo HEAD está pendiente de verificación.

**Límite:** la validación por ruta no elimina la carrera TOCTOU si otro actor
sustituye la raíz entre la comprobación y la operación; tampoco hace atómico
el borrado de Room e imágenes. Sigue siendo necesaria una limpieza recuperable
y la integración productiva del journal antes de considerar #56 resuelta.

## Protección adicional de importación — 2026-10-28 05:12 UTC

El almacén privado `exercise-images` se comprueba antes de preparar una importación
y **otra vez justo antes de activar** el directorio staged. Se rechazan enlaces
simbólicos (también colgantes) y rutas que existan sin ser directorios. La
comprobación evita mover una referencia simbólica al slot de preimagen o
reemplazarla accidentalmente; las pruebas Robolectric cubren el enlace presente
antes de importar y el enlace inyectado entre staging y activación, verificando
que Room y tema no se confirman y que el destino externo no cambia.

**Límites:** las comprobaciones de ruta no eliminan TOCTOU frente a cambios
concurrentes fuera de la barrera. La importación productiva sigue sin journal
instalado ni recuperación obligatoria antes de UI/seeding. CI de estos cambios
pendiente de resultado; no se declara prueba física Android 15/16.

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

## Regresión de staging no publicado — 2026-10-27 09:09 UTC

Se incorporan dos pruebas JVM en `BackupRecoveryJournalTest` (commit `7ebdbce`)
para fallos deterministas de sincronización **antes** de publicar `PREPARED`:

- fallo del `fsync` del directorio padre inmediatamente después de crear
  `.preparing`;
- fallo del `fsync` del directorio `.preparing` después de publicar
  `previous.zip`, pero antes de escribir el marcador.

En ambos casos se exige que el journal activo siga ausente, que el arranque
limpie exclusivamente la preparación incompleta **sin restaurar ni tocar datos
de dominio**, y que una preparación posterior pueda completarse. La CI del
nuevo HEAD debe verificarse antes de atribuir cobertura superada.

Estas regresiones no son muerte abrupta de proceso ni validación de
durabilidad en Android 15/16. La barrera global, recuperación anterior al
seeder/UI, sincronización física de directorios y pruebas de interrupción
real continúan siendo puertas de integración; #56 abierta y PR #57 draft.

## Sincronización de directorios de imágenes durante importación — 2026-10-27 10:06 UTC

PR #57 añade persistencia explícita de metadatos de directorio en
`RoomBackupDataSource`: después de escribir y sincronizar cada imagen staged,
sincroniza el directorio staged y su padre; al preservar el directorio anterior,
sincroniza el padre antes de activar el nuevo; tras activar el nuevo, vuelve a
sincronizar el padre antes de permitir el commit de Room. Si falla este último
`fsync`, el estado `activated` ya está marcado para que el rollback retire
las imágenes nuevas y restaure el directorio anterior, sincronizando su entrada.

Commits `3b80ad9`, `92fbbd2` y `db91bcc` incorporan el código, una
inyección determinista de fallos de `fsync` de directorio y una regresión
Robolectric que exige conservar sesión Room, imágenes anteriores y tema tanto
si falla la sincronización después de preservar el directorio anterior como
después de activar el nuevo; también exige reintento posterior exitoso.

**Límites y gates:** los fallos son simulados en Robolectric; no demuestran
persistencia real tras corte de alimentación ni compatibilidad de
`FileChannel.force(true)` con directorios en Android 15/16. Las escrituras
ordinarias de imágenes, las transacciones Room y DataStore requieren todavía
revisión de durabilidad y exclusión global. El `AppContainer` no instala
la barrera compartida ni recuperación antes de seeding/UI; la PR continúa
en borrador y la Issue #56 abierta. Comprobar CI del HEAD antes de dar por
validadas estas pruebas. Sin autorización de candidato APK ni merge.

## Lectura estricta de preferencias para backup — 2026-10-27

El flujo de tema usado por la UI mantiene su fallback a `SYSTEM` ante `IOException` de DataStore para permitir una visualización degradada. Esa tolerancia **no es válida para capturar una preimagen de recuperación**: convertir silenciosamente un error de lectura en `SYSTEM` podría sobrescribir la preferencia original al restaurar un backup.

La PR #57 introduce `UserPreferencesRepository.currentThemeModeForBackup()`, que lee DataStore sin capturar `IOException`. `RoomBackupDataSource.exportSnapshot()` y `replaceAll()` la utilizan para **abortar antes de publicar una instantánea incorrecta o de mutar Room/imágenes** si no puede leerse el tema. La regresión Robolectric `BackupPreferencesReadFailureTest` inyecta una lectura fallida, comprueba el fallback de UI, el rechazo de exportación/importación y la conservación del estado de Room.

**Límites:** esto no aporta durabilidad física ni convierte Room, DataStore e imágenes en una transacción atómica. Siguen pendientes barrera global, conexión de recuperación al arranque, confirmación COMMITTED y pruebas de cortes reales en Android 15/16. La CI del HEAD con estos cambios debe comprobarse antes de atribuirles validación.

## Durabilidad del alta ordinaria de imágenes — 2026-10-27

El alta de imágenes del usuario (`ExerciseImageManager.addUserImage`) sincronizaba
los bytes del archivo temporal, pero publicaba el nombre definitivo y el
registro Room **sin sincronizar la entrada de directorio**. Un corte abrupto
podría dejar metadatos que referencian un archivo cuyo renombrado no sobrevivió.

En PR #57, el commit `7fd5b21` sincroniza el directorio padre al crearlo y el
directorio de imágenes **después del rename y antes del insert Room**. Si falla
la sincronización, aborta el alta, retira el archivo no registrado e intenta
sincronizar la limpieza, conservando los errores de limpieza como suprimidos.
Las regresiones Robolectric `00d5c54`/`a4e06f3` comprueban fallo de fsync
tras el rename y antes de cualquier metadato Room, limpieza y reintento, y
fallo de fsync del directorio padre antes de escribir la imagen.

**Límites:** el borrado ordinario de imágenes y las operaciones de limpieza
de catálogos aún requieren una auditoría específica de durabilidad y
consistencia entre archivos y Room. El fsync de directorios debe verificarse
en Android 15/16: los tests Robolectric no prueban resistencia a un corte
eléctrico. No se ha conectado la barrera global ni la recuperación previa a
seeding/UI en `AppContainer`. Esta mejora no autoriza merge, candidato ni
cierre de #56; requiere CI del HEAD.

## Borrado ordinario de imágenes: recuperación de errores controlados — 2026-10-27

La auditoría de escritores detectó que `ExerciseImageManager.deleteUserImage`
borraba primero la fila Room y luego llamaba a `File.delete()` **sin comprobar
su resultado**. Si el borrado físico fallaba, el usuario recibía éxito mientras
el archivo permanecía huérfano. La PR #57 incorpora el commit `4289b1b`:
conserva el archivo hasta que Room confirme la baja; si el borrado físico
devuelve `false` o lanza una excepción, intenta reinsertar la fila original
en contexto no cancelable y propaga el error, incluyendo como suprimido
cualquier fallo adicional de restauración. Si el archivo ya faltaba, permite
retirar el metadato obsoleto. Dos pruebas Robolectric (`f9dd848`) cubren
fallo de eliminación, reintento y cancelación simulada con preservación de
bytes y referencia Room.

**Alcance estricto:** esto recupera errores *controlados*, no ofrece
atomicidad Room/archivos. Una muerte de proceso después de borrar la fila y
antes de retirar el archivo puede dejar un huérfano; la baja tampoco sincroniza
todavía los metadatos del directorio. Si falla la reinserción, la referencia
puede quedar ausente aunque se conserva el archivo, y el error se comunica.
Se requieren barrera global, recuperación de arranque, auditoría de limpieza
de catálogos y pruebas físicas Android 15/16. La CI del nuevo HEAD debe
comprobarse; no equivale a una prueba física. #56 permanece abierta, PR #57
en borrador; sin merge, APK, etiqueta ni release.

## Alta ordinaria: compensación tras publicación Room — 2026-10-27

La recuperación anterior del alta de imágenes asumía que cualquier excepción
alrededor de `exerciseDao.insertImage` implicaba que Room no había publicado la
fila. Esa premisa no cubre una excepción o cancelación **posterior** al insert:
el `catch` podía eliminar el archivo mientras Room seguía referenciándolo.

En la PR #57, `a65bc85` introduce una compensación no cancelable: ante un
error, consulta si la fila existe; si existe, intenta eliminarla y verifica
su ausencia **antes** de retirar el archivo. Si la consulta, la compensación
o la verificación fallan, conserva el archivo para no crear deliberadamente
una referencia Room colgante y añade el error como suprimido. Un punto de
inyección tras el insert permite probar esta ventana; `163c94c` añade dos
regresiones Robolectric para excepción y cancelación, con limpieza y reintento.

**Límites:** la compensación no sustituye una transacción Room/archivos ni
protege frente a muerte abrupta del proceso o escritores concurrentes. Si
falla la compensación, el alta comunica error pero puede conservar fila y
archivo; requiere reparación/recuperación posterior. Sigue pendiente conectar
la barrera compartida y recuperación antes del seeder/UI, auditar la baja
de imágenes y validar fsync/interrupción real en Android 15/16. Comprobar CI
del nuevo HEAD; #56 permanece abierta y PR #57 en borrador.

## Corrección incremental — 2026-10-27 15:10 Canarias

La auditoría de `ExerciseImageManager.deleteUserImage` detectó una ventana adicional
ante errores controlados: un hook de borrado podía eliminar los bytes y después
lanzar una excepción o cancelación. La compensación anterior reinsertaba
incondicionalmente la fila Room, creando una referencia a un archivo inexistente.

La corrección `bac10e9` restaura la fila solo si el archivo sigue presente;
`4bf2a64` añade dos regresiones Robolectric para excepción y cancelación
**después** de eliminar los bytes. Si el archivo ya desapareció, se conserva
la eliminación de metadatos y se propaga el error, sin inventar recuperación
de bytes que ya no existen.

**Límite:** esto protege la coherencia de referencias ante fallos controlados
en esa ventana, no la atomicidad de Room + sistema de archivos ni la
durabilidad del borrado tras corte de energía. Sigue faltando sincronizar
metadatos de directorio de bajas, integrar barrera y recuperación en
`AppContainer`, y validar cortes reales en Android 15/16. La Issue #56
permanece abierta y la PR #57 en borrador; no habilita merge ni candidato APK.

## Borrado ordinario: sincronización de directorio y comprobación de eliminación — 2026-10-27 16:10 Canarias

La revisión de `ExerciseImageManager.deleteUserImage` detectó que el camino
de éxito no sincronizaba la entrada del directorio después de retirar un archivo
de imagen. Además, una implementación de borrado que devolviese `true` sin
retirar realmente los bytes se consideraba exitosa y dejaba el archivo huérfano.

El commit `06dff7a` exige comprobar que el archivo desapareció tras
`removeUserImageFile` y sincronizar el directorio antes de comunicar éxito.
Si el borrado no se realizó, restaura la fila Room cuando el archivo sigue
presente. Si el archivo ya se eliminó pero falla el `fsync`, propaga el
error **sin reinsertar** una referencia a bytes inexistentes. El commit
`bc2bfd0` añade dos regresiones Robolectric: fallo de sincronización
posterior al borrado con reintento de alta y borrado que informa éxito sin
eliminar los bytes, con restauración de la referencia.

**Límites:** ni la sincronización de directorio ni la compensación controlada
son una transacción atómica entre Room y el sistema de archivos. Si se produce
una caída abrupta entre la baja de Room y el borrado, pueden quedar archivos
huérfanos; si falla la sincronización, la durabilidad del borrado no queda
demostrada. Es obligatorio validar `FileChannel.force(true)` en Android 15/16
y cortes reales. La barrera compartida y el recovery previo a seeding/UI aún
no están instalados en `AppContainer`. La CI del HEAD debe comprobarse;
Issue #56 abierta y PR #57 draft, sin merge, candidato, tag ni release.

## Validación estricta de preferencia persistida — 2026-10-27 17:11 UTC

La lectura estricta de DataStore para backups rechaza ahora también un valor persistido
de tema desconocido, en lugar de exportar silenciosamente `SYSTEM`. Una clave ausente
sigue significando el valor por defecto de primera instalación. El flujo de lectura
tolerante para mostrar la UI permanece sin cambios. Tres pruebas Robolectric
comprueban el rechazo sin mutar la preferencia, la clave ausente y el valor válido
(commits `5a3963d` y `4f901fc`). La CI del HEAD nuevo está pendiente de verificación.

**Esto solo evita capturar una preimagen incorrecta por corrupción lógica de DataStore.**
No demuestra una transacción entre Room, imágenes y preferencias ni resuelve
la recuperación productiva: siguen pendientes barrera global, conexión a
`AppContainer` y bloqueo de UI/seeding hasta recuperar, durabilidad física y
cortes reales de proceso Android 15/16. PR #57 continúa en borrador; #56 abierta.

## Integración incremental de escritores — 2026-10-27 19:08 UTC

La PR #57 incorpora en `DefaultAppContainer` un **único** `BackupDomainWriteBarrier`
para las mutaciones expuestas por `SessionRepository`, `RoutineRepository`,
`ExerciseCatalogRepository`, `CatalogMaintenanceRepository`, el almacén de limpieza
histórica y el seeder. Los delegados Room siguen siendo los originales; se utilizan
los adaptadores protegidos ya probados de manera aislada. La prueba
`AppContainerWriteBarrierWiringTest` protege la composición de los cuatro repositorios
de interfaz frente a regresiones que vuelvan a exponer implementaciones crudas.

**Este cambio no activa el journal ni la recuperación productiva.** El `BackupManager`
continúa utilizando `RoomBackupDataSource` directamente para evitar prometer aislamiento
incompleto. Las rutas de `ExerciseImageManager` (clase concreta expuesta a UI),
`UserPreferencesRepository.setThemeMode` (llamada desde `AppViewModel`),
lecturas/flows y cualquier acceso directo a DAO siguen fuera de la barrera.
`GymUpApp` tampoco espera una recuperación antes de construir pantallas y el seeder
aún no está detrás de un gate de recuperación (aunque su escritura ya comparte mutex).

**Siguiente integración obligatoria:** sustituir las exposiciones de imagen/tema por
interfaces o adaptadores protegidos compatibles con sus consumidores, auditar todos
los escritores y rutas DAO, conectar el gate de arranque antes de seeding y UI y solo
entonces instalar `BarrierProtectedJournaledBackupDataSource`. Probar fallos de
recuperación con UI bloqueada y reintento, validar CI del HEAD y ejecutar pruebas
instrumentadas/cortes reales en Android 15/16. No fusionar #57 ni cerrar #56 hasta
cumplir esas puertas. Sin APK, tag ni release.

## Integración parcial de imágenes y corrección de CI — 2026-10-27 20:08 UTC

La CI #40566359681 para el avance previo falló con **181 tests ejecutados, 1 fallo**:
`AppContainerWriteBarrierWiringTest` no fijaba SDK de Robolectric y fallaba
al resolver el SDK 36 del proyecto (`UnsupportedOperationException` en
`DefaultSdkProvider`). El test se fija explícitamente a SDK 35, coherente
con las demás pruebas Robolectric y con el mínimo Android soportado. Esto no
sustituye las pruebas instrumentadas Android 15/16.

`ExerciseImageManager` recibe ahora opcionalmente la instancia compartida
de `BackupDomainWriteBarrier` desde `DefaultAppContainer` y la mantiene
adquirida durante la operación **completa** de alta/baja de imagen, incluyendo
bytes privados, metadatos Room y compensaciones. Las construcciones de tests
sin barrera conservan su comportamiento anterior. Dos regresiones Robolectric
comprueban que el alta y la baja esperan a que termine una operación exclusiva
de backup, sin modificar filas ni archivos mientras esperan.

**Límite de seguridad:** la protección es local al proceso y solo afecta a
las mutaciones del gestor de imágenes que usa el contenedor. No protege aún
`UserPreferencesRepository.setThemeMode`, escrituras directas de DAO,
lecturas expuestas ni el arranque de UI/seeding frente a recuperación pendiente.
Por ello `BackupManager` sigue utilizando `RoomBackupDataSource` sin journal;
no se activa `JournaledBackupDataSource`, ni se declara atomicidad/durabilidad
física, ni se habilita merge, APK, etiqueta o release. La CI de los commits
nuevos debe verificarse; los cortes reales y fsync en Android 15/16 quedan
pendientes. Issue #56 abierta, PR #57 draft.

### Extensión de la barrera a preferencias de UI — 2026-10-27

`UserPreferencesRepository.setThemeMode` utiliza ahora el mismo mutex del
contenedor que las mutaciones Room e imágenes. `DefaultAppContainer` crea
un delegado separado **sin barrera** para `RoomBackupDataSource` (evita
anidamiento no reentrante cuando se instale el backup exclusivo) y expone
el repositorio protegido a `AppViewModel`. Ambos delegados usan el mismo
DataStore de contexto. Una prueba Robolectric comprueba que el cambio de tema
espera a que termine una operación exclusiva y no modifica preferencias
mientras espera.

**Pendiente:** la exportación/importación productiva sigue sin journal y no
adquiere todavía la barrera global. No se ha comprobado cobertura de todos
los accesos DAO ni se ha implementado el gate de recuperación previo a UI y
seeding. El hecho de que los escritores conocidos estén protegidos no permite
activar importación journaled hasta cerrar esos puntos y validar durabilidad
física e interrupciones reales en Android 15/16. #56 abierta, PR #57 draft.

## Auditoría del borrado de ejercicios con imágenes — 2026-10-27 21:08 UTC

`RoomCatalogMaintenanceRepository.deleteExercise` confirmaba la baja en Room y después
intentaba retirar cada imagen de usuario mediante `File.delete()` sin comprobar el
resultado. Un fallo de E/S o un falso positivo dejaba bytes privados huérfanos mientras
la operación comunicaba éxito. La corrección `c0656de` verifica la desaparición
real de cada archivo y sincroniza el directorio tras el borrado; si la eliminación
falla, propaga `IOException` en lugar de comunicar un éxito falso. Las regresiones
`2ad16d3` simulan `delete=false` y `delete=true` sin retirar el archivo.

**Limitación importante:** la transacción Room ya se ha confirmado cuando comienza
la limpieza de archivos. Por tanto, un error puede dejar el ejercicio eliminado de
Room y los bytes huérfanos; el error comunicado **no significa rollback**. Una
interrupción entre Room y la limpieza, o tras borrar solo parte de varias imágenes,
también puede dejar huérfanos. No se reinsertan ejercicios ni rutinas sin un
protocolo de recuperación que preserve sus relaciones. Falta diseñar una limpieza
idempotente recuperable/journaled y probar fsync real en Android 15/16. Esta
corrección parcial no autoriza integrar la PR #57 ni cerrar #56; mantenerla draft.

## Prevalidación de rutas de imágenes en bajas de ejercicios — 2026-10-27 22:10 UTC

La auditoría de `RoomCatalogMaintenanceRepository.deleteExercise` detectó que las claves
`USER` se filtraban por prefijo y luego se convertían directamente en rutas locales.
Una clave corrupta con `user:../` podía escapar de `exercise-images` y un registro
`USER` sin prefijo se omitía silenciosamente de la limpieza. La baja ahora valida
**todas** las claves de imágenes `USER` antes de confirmar la transacción Room:
requiere prefijo `user:`, nombre no vacío y sin separadores, `.`, `..` ni NUL.
Ante una clave no válida aborta sin eliminar el ejercicio, sus referencias de rutina
ni sus metadatos de imagen. Dos regresiones Robolectric comprueban recorrido fuera
del directorio y clave sin prefijo, con preservación de Room y bytes externos.

**Límites:** es una protección preventiva de integridad y rutas, no recuperación
journaled ni rollback del borrado ya confirmado. Las claves válidas todavía pueden
dejar archivos huérfanos si falla la limpieza o el proceso muere después de la baja
Room. Tampoco cubre otros lectores/escritores que construyan rutas directamente.
Mantener #56 abierta, PR #57 draft, y pendientes journal, gate de arranque,
auditoría integral y validación física Android 15/16.

## Protección frente a objetos no regulares en la limpieza — 2026-10-27 23:15 UTC

La auditoría de la baja de ejercicios detectó que una clave de imagen con sintaxis
válida todavía podía apuntar a un **directorio** o enlace simbólico dentro de
`exercise-images`. La prevalidación ahora rechaza objetos existentes que no sean
archivos regulares o que sean enlaces simbólicos **antes** de confirmar la baja Room
(commit `a15dcda`), y la limpieza repite la comprobación inmediatamente antes de
borrar para limitar cambios de tipo entre validación y eliminación. El test
Robolectric `84d44f0` verifica que una ruta correspondiente a un directorio
conserva ejercicio y referencias Room y no elimina el directorio.

**Limitaciones:** la comprobación no constituye una operación atómica de filesystem;
no protege frente a cambios externos entre comprobación y borrado. Tampoco resuelve
el riesgo principal de imágenes huérfanas por baja Room confirmada antes de limpiar
archivos, ni sustituye journal, recuperación de arranque y pruebas reales Android 15/16.
La PR permanece en borrador y #56 abierta.

## Enlaces simbólicos colgantes en la baja de ejercicios — 2026-10-28 00:09 UTC

La comprobación previa de tipo de archivo de `RoomCatalogMaintenanceRepository.deleteExercise`
usaba `File.exists()` antes de `Files.isSymbolicLink`. Como `exists()` sigue el
enlace, un **symlink colgante** devolvía `false` y pasaba como imagen ausente;
la baja Room se confirmaba sin advertir que la entrada de filesystem seguía allí.
El commit `28649f0` comprueba el enlace **antes** de `exists()`, tanto en
prevalidación como en la limpieza posterior a la transacción. La regresión
`33bc4f0` crea un enlace simbólico colgante y exige conservar el ejercicio,
sus metadatos Room y el enlace al rechazar la baja.

**Alcance y límite:** se impide el éxito silencioso ante un enlace colgante
detectable, pero no se garantiza ausencia de carreras TOCTOU entre las
comprobaciones y el borrado. No se ha integrado journal de limpieza ni
recuperación productiva de importaciones; siguen pendientes recuperación antes
de UI/seeding, auditoría de escritores/DAO y cortes reales Android 15/16.
La PR #57 permanece en borrador y la Issue #56 abierta.

## Auditoría incremental — 2026-10-28 01:12 UTC: borrado individual de imágenes

Se detectó que `ExerciseImageManager.deleteUserImage` utilizaba la clave persistida de Room para construir una ruta de fichero sin validarla antes de borrar la fila. Una referencia corrupta `user:../...` podía apuntar fuera de `exercise-images`; un directorio o enlace simbólico (incluido el colgante, invisible para `File.exists`) podía provocar una baja de metadatos sin limpieza válida. Se introduce validación previa de prefijo, nombre simple y tipo de objeto del sistema de archivos, coherente con la protección del borrado de ejercicios. Tres pruebas Robolectric cubren traversal, symlink colgante y directorio, verificando conservación de la fila Room y del objeto original. La CI del nuevo HEAD queda pendiente de comprobación.

**Límite:** esta prevalidación no es transacción atómica ni journal; no elimina la ventana entre borrado de Room y limpieza física ante corte abrupto ni protege frente a sustituciones concurrentes del fichero fuera de la barrera de dominio. La recuperación productiva, el gate antes de UI/seeding, la auditoría restante de DAO y la prueba física Android 15/16 siguen pendientes. Issue #56 abierta, PR #57 draft, sin merge ni candidato APK.

## Protección del journal frente a enlaces simbólicos — 2026-10-28 02:08 UTC

La auditoría de `BackupRecoveryJournal` identificó que `File.exists()` sigue enlaces simbólicos y que `deleteRecursively()` sobre directorios de preparación o retirada no debe recibir rutas que apunten fuera del área privada de recuperación. El cambio `550103b` rechaza enlaces simbólicos —también colgantes— en el directorio activo, `.preparing`, `.retired`, `previous.zip`, `state` y temporales de escritura/confirmación antes de leer, sobrescribir o limpiar. La regresión `78496ce` incorpora cinco pruebas JVM para journal activo enlazado, limpieza de `.retired`/`.preparing`, preimagen enlazada y temporal colgante, preservando datos externos y el estado PREPARED. Además, `027d85d`/`d7afdff` incorporan una prevalidación de rutas en `BackupRecoveryCoordinator` **antes** de la limpieza best-effort, que de otro modo silenciaría un fallo de `.retired`; `ced5d10` prueba que el arranque se bloquea sin tocar datos externos.

**Alcance:** defensa frente a rutas ya alteradas antes de la operación; no elimina carreras TOCTOU entre comprobación y acceso, no protege frente a otro proceso con acceso al sandbox y no constituye por sí misma recuperación duradera. La CI del HEAD con estos cambios debe verificarse. Sigue pendiente conectar el journal al backup real, impedir UI/seeding antes de la recuperación, auditar escrituras directas y probar interrupciones físicas Android 15/16. Mantener #56 abierta y PR #57 en borrador.

## Limpieza fail-closed de artefactos internos del journal — 2026-10-28 03:10 UTC

La protección previa rechazaba symlinks en las rutas de directorio, pero las llamadas
`deleteRecursively()` para `.preparing` y `.retired` no verificaban **sus entradas
internas**. Se sustituye por limpieza explícita de los únicos archivos planos
permitidos (`previous.zip`, `state` y sus temporales `.tmp`), con prevalidación
completa: symlinks internos (incluso colgantes), subdirectorios y nombres ajenos
provocan fallo cerrado **antes de eliminar ninguna entrada**. Se añaden cuatro
regresiones JVM: enlace en `.retired`, enlace colgante en `.preparing`,
subdirectorio inesperado y limpieza correcta de temporales conocidos.

**Límites:** la comprobación previa no es atómica respecto a actores externos
(TOCTOU); un fallo de borrado a mitad de la limpieza puede dejar restos que se
reintentan o requieren intervención, pero no autoriza a descartar una preimagen
PREPARED. La cobertura JVM requiere CI del HEAD nuevo; no se han realizado
pruebas instrumentadas ni físicas. Continúan pendientes integración productiva
del journal, recuperación obligatoria antes de UI/seeding, auditoría de
escritores/DAO, limpieza recuperable de imágenes y cortes reales Android 15/16.

## Exportación fail-closed de imágenes USER — 2026-10-28 04:12 UTC

La auditoría de la preimagen usada por `RoomBackupDataSource.exportSnapshot` detectó que
`UserExerciseImageStore.readRequired` comprobaba únicamente `File.isFile`, que sigue
enlaces simbólicos. Una referencia `USER` a un symlink dentro de `exercise-images`
podía hacer que una exportación ordinaria o la captura de preimagen para el journal
leyera bytes de otro archivo privado. Se valida explícitamente el formato de clave
`user:UUID`, se rechaza el directorio raíz enlazado y se comprueba el archivo con
`Files.isSymbolicLink` **antes** de leerlo, incluyendo enlaces colgantes (commit
`585f8ca`). Dos pruebas Robolectric cubren symlink válido y colgante, con conservación
de metadatos Room y del archivo externo (`fb9164f`, ajuste de import `44fd94f`).

**Límites:** la protección es una comprobación preventiva, no una apertura atómica
con `O_NOFOLLOW` ni una defensa frente a sustituciones TOCTOU por otro proceso.
No demuestra una instantánea consistente ante escritores DAO directos ni activa
el journal productivo. Se debe verificar la CI del HEAD con estas pruebas;
continúan pendientes recuperación antes de UI/seeding, limpieza recuperable,
auditoría de escritores y cortes reales en Android 15/16. #56 abierta y PR #57 draft.

## Auditoría de lectura de imágenes y raíz de almacenamiento — 2026-10-28 06:10 UTC

La auditoría de `ExerciseImageManager` detectó una ruta adicional de acceso a ficheros
fuera del mecanismo de exportación: `observeImages` construía un `localFile`
directamente desde `storageKey` y aceptaba cualquier `File.isFile`, que sigue
symlinks. Una fila Room malformada (`user:../...`) o un symlink interno podía
exponer bytes de otro fichero privado a la galería y a `readBytes`. Además,
`addUserImage` permitía que la propia raíz `exercise-images` fuese un symlink,
redireccionando la escritura fuera del directorio esperado.

La corrección comprueba que la raíz no sea un enlace simbólico antes de crear
imágenes, valida las claves de lectura y rechaza directorios, symlinks y rutas
fuera de la raíz al exponer `localFile` o leer bytes. `readBytes` tampoco confía
en un `File` arbitrario entregado por un llamador. La baja individual rechaza
también la raíz enlazada. Cuatro pruebas Robolectric cubren raíz enlazada,
referencia traversal, enlace a fichero externo y lectura ordinaria.

**Límites:** el `File` expuesto a Compose todavía puede sustituirse después de
la comprobación (TOCTOU); la galería usa `BitmapFactory.decodeFile`, no apertura
atómica sin seguimiento de enlaces. No hay journal productivo, gate de arranque
ni garantía de durabilidad ante corte. Esta mitigación no autoriza fusionar PR #57,
cerrar #56 o generar un APK; las pruebas físicas Android 15/16 siguen pendientes.

## Lectura de imágenes USER con descriptor sin seguimiento — 2026-10-28 07:11 UTC

La validación de `safeReadableUserImageFile` no impide que el archivo final
sea sustituido por un enlace simbólico entre la comprobación y
`readBytes`. Se cambia la lectura a `Files.newByteChannel` con
`LinkOption.NOFOLLOW_LINKS` sobre el componente final, devolviendo
`null` si falla la apertura. La lectura queda limitada a 10 MiB
también cuando la fila persistida apunta a un archivo alterado.
Dos regresiones Robolectric cubren sustitución determinista por symlink
justo antes de abrir y archivo persistido sobredimensionado.

**Límite:** `NOFOLLOW_LINKS` protege el componente final al abrir,
pero no garantiza protección contra sustitución concurrente de
directorios antecesores ni cubre la ruta de `BitmapFactory.decodeFile`
utilizada por Compose. Esta mejora aislada no activa el journal en
producción ni elimina la necesidad de gate previo a UI/seeding,
auditoría DAO, limpieza recuperable y pruebas reales de interrupción
en Android 15/16. #56 sigue abierta y PR #57 en borrador.

## Galería Compose sin lectura de rutas mutables — 2026-10-28 08:10 UTC

La galería todavía invocaba `BitmapFactory.decodeFile(localFile.absolutePath)`
aunque `ExerciseImageManager.readBytes` ya utilizaba apertura con
`NOFOLLOW_LINKS` y límite de 10 MiB. Entre observar la fila Room y pintar
Compose, un enlace simbólico podía sustituir el archivo y hacer que el
`decodeFile` siguiera una ruta ajena a la imagen.

La PR #57 cambia la galería para obtener bytes exclusivamente mediante
`manager.readBytes(item)` y decodificarlos con `BitmapFactory.decodeByteArray`.
El trabajo de lectura y decodificación se realiza en `Dispatchers.IO` mediante
`produceState`, sin bloquear el hilo de composición. Dos regresiones Robolectric
verifican una imagen PNG válida y la sustitución determinista por symlink
entre observación y apertura. El archivo externo no se altera.

**Límites:** `NOFOLLOW_LINKS` protege el componente final, no el cambio
concurrente de directorios antecesores. El límite de bytes comprimidos no
limita por sí solo las dimensiones del bitmap decodificado. La CI del nuevo
HEAD debe verificarse; no se han ejecutado pruebas físicas. Esta corrección
no conecta el journal productivo, no instala recuperación antes de UI/seeding,
ni resuelve la limpieza recuperable, auditoría DAO o cortes Android 15/16.
La Issue #56 permanece abierta y la PR #57 en borrador.

## Exportación USER: descriptor sin seguimiento tras preflight — 2026-10-28 10:15 UTC

`UserExerciseImageStore.readRequired` comprobaba `Files.isSymbolicLink` y
`File.isFile` antes de `File.readBytes()`, que seguía enlaces si un fichero
era sustituido entre ambas operaciones. La exportación de backup y la captura
de la preimagen de recuperación comparten esta ruta. Ahora se abre el archivo
final mediante `Files.newInputStream(..., LinkOption.NOFOLLOW_LINKS)`; un fallo
de apertura aborta la exportación sin modificar Room ni DataStore (commit
`c1a53d6`). La regresión Robolectric `27a054e` sustituye de forma
determinista el archivo por un enlace después de la validación y verifica que
no se leen bytes externos ni se altera la referencia Room.

**Límites:** la apertura no protege ante sustitución concurrente de directorios
antecesores, ni establece un snapshot global consistente; el journal aún no
está instalado en AppContainer y falta gate de arranque antes de UI/seeding.
La prueba necesita CI del HEAD; no acredita corte físico ni durabilidad Android.
Mantener #56 abierta y PR #57 draft.

## Lectura acotada de preimágenes de backup — 2026-10-28 12:15 UTC

La exportación de backups y la captura de preimagen para el journal usaban
`InputStream.readBytes()` sobre archivos USER persistidos, sin límite de tamaño.
Aunque la incorporación ordinaria de imágenes restringe a 10 MiB, un fichero
corrupto o sustituido después del alta podía provocar una lectura arbitrariamente
grande y presión de memoria antes de preparar la recuperación. La lectura
`NOFOLLOW_LINKS` ahora consume bloques acotados y **aborta antes de superar
10 MiB**, sin publicar un backup parcial ni alterar Room/DataStore (commit
`0f0e1fd`). La regresión Robolectric crea un archivo disperso de 10 MiB + 1 byte
y verifica fallo cerrado, conservación de la referencia Room y del tema.

**Límites:** se controla el tamaño de bytes de cada imagen USER, no el tamaño
global de un backup ni los directorios antecesores sujetos a TOCTOU. No se ha
conectado el journal al flujo productivo ni se ha ejecutado una prueba física de
muerte de proceso. La PR #57 sigue draft y la Issue #56 abierta.

## Verificación de contenido al publicar imágenes USER — 2026-10-28 21:08 UTC

La auditoría de `ExerciseImageManager.addUserImage` identificó que, después de escribir y sincronizar el temporal, la validación solo comprobaba su longitud y tipo regular. Una sustitución o alteración del temporal que conservase exactamente el mismo tamaño podía publicarse como imagen válida y recibir una referencia Room, a diferencia de la validación de bytes ya incorporada al staging de importación.

El commit `723bc82` reabre el temporal con `NOFOLLOW_LINKS` después del punto de inyección y compara los bytes completos y el tamaño del descriptor con la imagen original **antes** del rename y del alta Room. La regresión Robolectric `a788020` sustituye los bytes por otros de idéntica longitud, exige fallo cerrado sin fila Room ni archivo publicado y verifica que un reintento normal funciona.

**Límites:** esta comprobación no hace atómico el rename frente a otro proceso ni resuelve la durabilidad de metadatos, los escritores directos no auditados, la limpieza recuperable ni los cortes físicos de proceso. El resultado de CI del HEAD actualizado debe verificarse antes de atribuir validación. Mantener #56 abierta y PR #57 en borrador, sin merge ni candidato APK.

## Exportación con journal COMMITTED pendiente de limpieza — 2026-10-28 23:14 UTC

Un journal `COMMITTED` con checksum válido significa que la operación ya eligió
el estado de dominio autoritativo. Si la limpieza de artefactos antiguos falla,
no debe impedir una **exportación de solo lectura**: la exportación no modifica
el journal ni restaura la preimagen. `BackupRecoveryCoordinator.readSnapshotIfRecovered`
acepta ahora `Absent` o `Committed` verificado, y sigue rechazando
`PREPARED`, marcadores desconocidos y archivos corruptos. El cambio no
permite iniciar una nueva importación sobre un journal aún presente; esa
operación mantiene su puerta de limpieza previa.

Regresiones JVM comprueban la exportación en `COMMITTED` sin retirar el
journal y el rechazo de `PREPARED` y `COMMITTED` corrupto antes de invocar
la lectura de dominio. Esta corrección no elimina las ventanas de lectura de
UI durante importación en caliente, ni aporta durabilidad ante muerte real de
proceso, limpieza recuperable de imágenes o validación física Android 15/16.
La Issue #56 y la PR #57 siguen abiertas/en borrador.

## Bloqueo de escritores tras recuperación fallida en caliente — 2026-10-29 00:20 UTC

`BarrierProtectedJournaledBackupDataSource` mantiene la exclusión del mismo
`BackupDomainWriteBarrier` durante un reintento de recuperación si falla una
importación. La máquina de estados ya intenta rollback; el reintento adicional
comprueba y recupera un posible `PREPARED` antes de liberar a los escritores.
Si también falla, la barrera marca la instancia como **recuperación pendiente**
y rechaza nuevas escrituras cooperantes, exportaciones e importaciones hasta
que `recoverBeforeDataAccess` termine correctamente (habitualmente tras
reiniciar la app). Si el rollback y la recuperación terminan, no se bloquean
operaciones posteriores. El error original se conserva y se adjunta el fallo
de recuperación como excepción suprimida.

Dos pruebas JVM cubren recuperación fallida seguida de bloqueo y recuperación
posterior, y rollback exitoso que no bloquea escritores. **No se afirma CI
validada hasta que concluya el workflow del HEAD.**

**Límites críticos:** la barrera no intercepta lecturas/flows de la UI ya
compuesta, no resuelve un marcador COMMITTED ambiguo tras un error de fsync,
ni convierte en duradera la limpieza de imágenes o el journal. Tampoco cubre
escritores que eludan la composición, otros procesos o corte de alimentación.
#56 permanece abierta y PR #57 draft; faltan gates de visibilidad, auditoría
DAO, limpieza recuperable y cortes físicos Android 15/16.
