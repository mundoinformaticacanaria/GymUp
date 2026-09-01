# GymUp — Arquitectura técnica v1

Estado: **vigente para implementación**  
Issue origen: #2

## 1. Objetivo

Traducir el contrato funcional cerrado a una estructura de implementación que permita desarrollar el MVP sin decisiones arquitectónicas implícitas.

## 2. Capas

### UI / feature

Responsable de:

- renderizar estado;
- recoger eventos;
- navegación;
- dialogs/confirmaciones;
- lanzar intents SAF/ShareSheet/Photo Picker;
- accesibilidad y ergonomía.

No contiene SQL ni reglas complejas de negocio.

### Domain

Contiene:

- modelos de dominio compartidos;
- contratos de repositorio;
- use cases con reglas no triviales;
- cálculo de estado de ejercicio/sesión;
- selección de precarga histórica;
- validación de RIR;
- duplicación/creación desde rutina;
- reglas de maestros desactivados;
- normalización de nombres;
- saneado de nombres de archivo.

Debe ser testeable con JVM sin Android siempre que sea razonable.

### Data

Contiene:

- Room entities/DAO/database;
- mappers Entity ↔ Domain;
- implementaciones de repositorio;
- DataStore;
- serialización de informes/backups;
- almacenamiento privado de imágenes;
- transacciones.

## 3. Flujo de dependencias

```text
Compose Screen
   ↓ events
ViewModel
   ↓
UseCase / Repository interface
   ↓
Repository implementation
   ↓
Room / DataStore / Files
```

Las capas inferiores no importan tipos de Compose.

## 4. AppContainer

`GymUpApplication` construye un `AppContainer` con dependencias singleton de proceso:

- `GymUpDatabase`
- `UserPreferencesRepository`
- repositorios de maestros/sesiones/rutinas
- `SessionReportExporter`
- `BackupManager`
- `ExerciseImageStore`
- `Json`
- reloj técnico inyectable cuando sea necesario

Los ViewModels reciben dependencias por constructor mediante factories explícitas.

### Estado implementado

El MVP sigue siendo un monolito modular en un único módulo Gradle `:app`, conforme a ADR-006. La separación interna vigente es:

- contratos `SessionRepository` y `RoutineRepository` segregados por responsabilidad;
- implementaciones Room `RoomSessionRepository` y `RoomRoutineRepository` independientes;
- `AppViewModel` para preferencia global de tema;
- `RoutineListViewModel` y `RoutineEditorViewModel` para el flujo de rutinas;
- `SaveRoutineUseCase` y `FilterRoutineExercisesUseCase` entre UI y persistencia.

La migración a varios módulos Gradle continúa pospuesta hasta que exista una razón medible de tiempos de build, ownership o reutilización. La separación de paquetes y contratos no debe confundirse con microservicios ni con una arquitectura distribuida.

## 5. Navegación v1

Destinos principales:

- `home`
- `sessions`
- `session/new`
- `session/{sessionId}`
- `routines`
- `routine/new`
- `routine/{routineId}`
- `exercises`
- `exercise/{exerciseId}`
- `history`
- `exercise-history/{exerciseId}`
- `settings`

Las rutas solo transportan IDs/flags simples. Las entidades se recargan por ID para evitar estados obsoletos.

## 6. Home

El Home observa sesiones de hoy y aplica prioridad:

1. `IN_PROGRESS`
2. `PLANNED`
3. `REALIZED`

La UI no calcula estados; recibe un modelo ya ordenado desde ViewModel/use case.

## 7. Sesiones

### Crear

Orígenes:

- vacía;
- rutina;
- duplicar sesión.

La creación persiste primero cabecera + lista inicial de ejercicios/series en una transacción. Los objetivos se materializan en ese momento desde histórico/master.

### Editar

Cada cambio de ejecución real:

1. valida tipo/rango;
2. actualiza serie;
3. recalcula estado derivado de ejercicio;
4. recalcula resultado de sesión;
5. si era `PLANNED` y aparece primer dato real, cambia a `IN_PROGRESS`.

Todo lo anterior se ejecuta transaccionalmente cuando afecte a varias tablas.

### Finalizar

`FinalizeSessionUseCase`:

1. detecta series realizadas con RIR obligatorio pendiente;
2. si existen, devuelve un resultado de validación a UI y no finaliza;
3. si no, marca estado operativo `REALIZED`;
4. mantiene resultado derivado `NOT_STARTED/PARTIAL/COMPLETED` según datos reales.

## 8. Precarga histórica

`ResolveExercisePresetUseCase` recibe:

- `exerciseId`
- `sessionDateEpochDay`
- `orderInDay`

Consulta última instancia `COMPLETED` estrictamente anterior. Devuelve:

- número de series;
- por serie: carga objetivo + medición objetivo + modalidad/unidad;

Si no hay histórico, usa defaults maestro. Si tampoco, vacío.

Series añadidas posteriormente a N copian objetivos de la última serie de esa referencia materializada, según contrato.

## 9. Búsqueda

Se mantienen claves normalizadas en DB para `nombre_es`/`nombre_en`.

Pipeline:

1. normalizar query;
2. filtrar activos;
3. coincidencia empieza-por;
4. coincidencia contiene;
5. dentro de cada bloque: favorito → usado antes por recencia → presente en rutina → resto;
6. desempate alfabético estable.

La consulta puede combinar SQL + orden en dominio si simplifica mantenibilidad, siempre con dataset pequeño (60–100 inicial y crecimiento local moderado).

## 10. Histórico y gráficas

El repositorio de histórico devuelve únicamente ejecuciones con al menos una serie realizada.

Para últimas 10:

- orden temporal descendente para seleccionar;
- reordenar ascendente para dibujar eje X;
- cada línea corresponde a posición de serie;
- separar por `load_mode` o `measurement_unit`;
- no conectar huecos donde una serie no existe;
- ejecución parcial marcada visualmente.

Canvas Compose recibe un modelo de puntos ya agrupado; no decide comparabilidad.

## 11. Exportación

`SessionReportExporter` convierte una sesión `REALIZED` a DTO serializable v1 y genera bytes JSON UTF-8.

La UI decide destino:

- Guardar → SAF;
- Compartir → fichero temporal privado + `FileProvider`.

## 12. Backup/importación

`BackupManager` no accede directamente a Compose. Opera con streams/URIs abstraídos desde capa Android.

Validación completa antes de replace-all. Los cambios de DB se encapsulan en repositorio/importador transaccional.

## 13. Imágenes

`ExerciseImageStore` expone referencias lógicas:

- seed: `asset:<name>` o resource key;
- personalizada: `user:<uuid>`.

La UI resuelve la referencia a stream/URI interno; los datos de dominio no almacenan rutas absolutas.

## 14. Preferencias

DataStore Preferences v1:

- `theme_mode`: `SYSTEM | LIGHT | DARK`
- reserva para preferencias de UX futuras

No se usa DataStore para datos de entrenamiento.

## 15. Manejo de errores

- Errores de validación de dominio se modelan como resultados sellados/typed errors.
- Fallos IO/import/export se capturan en repositorio y se presentan con mensaje accionable.
- No se silencian fallos de persistencia.
- Nunca se destruyen datos actuales como fallback de una importación/migración fallida.

## 16. Logging

Solo logging técnico de desarrollo/release mínimo. No registrar:

- contenido completo de backup/informe;
- notas del usuario;
- nombres de imágenes/rutas externas completas;
- datos sensibles innecesarios.

## 17. Rendimiento

El volumen esperado es pequeño. Prioridades:

- índices en fecha/orden/IDs normalizados;
- Flow solo para pantallas que necesitan reactividad;
- queries específicas en vez de cargar toda la DB;
- imágenes decodificadas con tamaño acorde a UI;
- export/import en dispatcher IO.

## 18. Criterio para introducir otra dependencia

Una nueva librería solo entra si:

1. resuelve una necesidad real del contrato;
2. es gratuita/libre y mantenida;
3. reduce complejidad neta;
4. no compromete offline/privacidad;
5. su licencia es compatible;
6. se documenta si la decisión es duradera.
