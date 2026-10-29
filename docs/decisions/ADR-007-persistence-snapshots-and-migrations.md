# ADR-007 — Persistencia, snapshots y migraciones

Estado: **Aceptado**  
Fecha: 2026-08-20  
Issue: #2

## Contexto

GymUp debe conservar el significado histórico aunque cambien nombres, grupos, equipos o defaults de los maestros. También debe soportar edición de sesiones pasadas, precargas temporales por `fecha + orden_en_dia`, backup completo e importación replace-all.

## Decisión

### Base de datos

- Room 2.8.4 sobre SQLite.
- Una única base de datos local: `gymup.db`.
- WAL habilitado mediante la configuración estándar de Room.
- Claves primarias de dominio como UUID almacenados en `TEXT`.
- Fechas de sesión almacenadas como `epochDay` (`INTEGER`).
- Instantes técnicos (`created_at`, `updated_at`) en epoch milliseconds cuando sean útiles; nunca se usan como hora de entrenamiento.

Los UUID se usan porque los IDs aparecen en JSON de sesión/backup y deben permanecer estables después de exportar/importar.

### Tablas maestras

Como mínimo:

- `session_types`
- `muscle_groups`
- `equipment`
- `exercises`
- `exercise_images`
- `routines`
- `routine_exercises`

Los maestros históricamente utilizados se desactivan, no se borran. El borrado físico solo se permite donde el contrato lo autoriza y no existe histórico.

### Datos transaccionales

Como mínimo:

- `sessions`
- `session_exercises`
- `session_sets`

`sessions` almacena el estado operativo. El resultado de ejecución se mantiene como valor derivado/denormalizado para filtros eficientes y se recalcula transaccionalmente al modificar trabajo real.

El estado de ejercicio se deriva de sus series; no existe un override manual v1.

### Snapshots históricos

`session_exercises` conserva snapshots independientes del maestro:

- `exercise_name_es_snapshot`
- `exercise_name_en_snapshot`
- `muscle_group_name_snapshot`
- `equipment_name_snapshot`
- `default_load_mode_snapshot`
- `default_measurement_unit_snapshot`
- `rir_required_snapshot`
- información técnica/descriptiva necesaria para interpretar esa instancia

La referencia al `exercise_id` se mantiene para continuidad estadística, pero la presentación histórica usa snapshots.

La sesión conserva `session_type_id` y `session_type_name_snapshot`.

Cambiar un maestro nunca actualiza automáticamente snapshots ya creados.

### Series

Cada `session_set` almacena de forma explícita:

- posición dentro del ejercicio;
- modalidad de carga aplicable a esa serie;
- unidad de medición aplicable a esa serie;
- carga objetivo nullable;
- carga real nullable;
- medición objetivo nullable;
- medición real nullable;
- RIR nullable;
- descanso override nullable;
- marca técnica de confirmación/ejecución cuando sea necesaria para distinguir `0` real de ausencia de dato.

Los valores reales nunca se generan por inferencia. `Cumplido` es una acción de dominio que copia objetivo → real explícitamente.

### Orden

- `sessions.order_in_day`: entero positivo y único por `session_date_epoch_day`.
- `session_exercises.position`: entero positivo y único por sesión.
- `session_sets.position`: entero positivo y único por ejercicio de sesión.
- `routine_exercises.position`: entero positivo y único por rutina.

Las reordenaciones se ejecutan dentro de transacción para no dejar posiciones duplicadas intermedias.

### Unicidad normalizada

Los maestros con nombres únicos almacenan una columna normalizada además del valor visible.

Normalización v1:

1. `trim()` del valor de entrada antes de guardar;
2. Unicode NFD;
3. eliminación de marcas combinantes;
4. lowercase con `Locale.ROOT`.

No se introduce fuzzy matching ni conversión semántica. Las restricciones únicas se aplican a las columnas normalizadas correspondientes.

### Borrado y FKs

- Borrar una sesión elimina en cascada sus instancias y series.
- Borrar una rutina elimina en cascada sus filas de orden.
- Maestros con histórico no se eliminan por cascada.
- La baja de ejercicio mantiene `exercise_id` disponible para estadísticas históricas.
- Si se autoriza borrado físico de ejercicio sin histórico pero presente en rutinas, la eliminación de sus referencias de rutina y del maestro ocurre en una única transacción.

### Migraciones

- `fallbackToDestructiveMigration` queda prohibido para builds de producción.
- Cada cambio de esquema incrementa versión de Room y aporta migración explícita.
- Se exporta schema de Room al repositorio para revisar diffs.
- Las migraciones se prueban al menos desde la versión anterior soportada.
- Antes de una migración que pueda perder significado histórico se requiere ADR adicional.

### Precarga histórica

La selección de fuente se implementa como operación de dominio/repositorio usando `(session_date_epoch_day, order_in_day)` y solo ejercicios `COMPLETED` estrictamente anteriores.

Los objetivos se materializan al crear/agregar el ejercicio. Cambiar posteriormente fecha/orden no los recalcula automáticamente; solo la acción explícita aprobada por contrato puede hacerlo.

### Limpieza histórica

El borrado masivo por fecha:

1. calcula recuentos/estimación;
2. confirma en UI;
3. elimina transaccionalmente sesiones con fecha estrictamente anterior al corte;
4. ejecuta checkpoint/`VACUUM` fuera de la transacción cuando sea seguro;
5. informa del resultado real.

## Consecuencias

- El histórico es resistente a cambios de maestros.
- Los informes y backups disponen de IDs estables.
- El esquema contiene cierta duplicación deliberada por snapshots.
- Las operaciones de reordenación/importación requieren transacciones cuidadosas.
- No se permite simplificar migraciones destruyendo datos existentes.
