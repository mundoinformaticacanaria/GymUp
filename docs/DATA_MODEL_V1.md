# GymUp — Modelo de datos v1

Estado: **vigente para implementación**  
Issue origen: #2

Este documento fija el modelo lógico mínimo. Los nombres exactos de clases Kotlin pueden variar, pero no puede cambiarse la semántica sin actualizar este documento/ADR.

## Convenciones

- IDs de dominio: UUID string (`TEXT`).
- Booleanos SQLite: `INTEGER 0/1`.
- Fecha de sesión: `epochDay` (`INTEGER`).
- Orden visible: enteros positivos 1..N.
- Kg: `REAL` validado a máximo 2 decimales en dominio/UI.
- Reps/segundos: `INTEGER >= 0`.
- Enums: `TEXT` con valores cerrados y converters Room.
- `created_at`/`updated_at`: epoch millis técnicos cuando se incluyan.

## Enums de dominio

### SessionOperationalState

- `PLANNED`
- `IN_PROGRESS`
- `REALIZED`

### SessionExecutionResult

- `NOT_STARTED`
- `PARTIAL`
- `COMPLETED`

### ExerciseExecutionState

Derivado, no editable:

- `NOT_PERFORMED`
- `PARTIAL`
- `COMPLETED`

### LoadMode

- `KG_TOTAL`
- `KG_PER_HAND`
- `KG_PER_SIDE`
- `BODYWEIGHT`
- `BODYWEIGHT_PLUS_LOAD`
- `BODYWEIGHT_MINUS_ASSISTANCE`
- `NO_WEIGHT`

### MeasurementUnit

- `REPETITIONS`
- `REPETITIONS_PER_SIDE`
- `SECONDS`
- `SECONDS_PER_SIDE`

### ThemeMode

- `SYSTEM`
- `LIGHT`
- `DARK`

## 1. session_types

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| name | TEXT | no vacío |
| normalized_name | TEXT UNIQUE | case/diacritics-insensitive |
| is_active | INTEGER | default 1 |
| is_protected_other | INTEGER | solo `Otro` seed = 1 |
| created_at | INTEGER | técnico |
| updated_at | INTEGER | técnico |

Seed: Fuerza, Hipertrofia, Cardio, Movilidad, Deporte, Recuperación, Otro.

`Otro` no puede renombrarse/desactivarse/borrarse.

## 2. muscle_groups

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| name | TEXT | no vacío |
| normalized_name | TEXT UNIQUE | normalizado |
| is_active | INTEGER | default 1 |
| created_at | INTEGER | técnico |
| updated_at | INTEGER | técnico |

Seed: Pecho, Espalda, Hombro, Bíceps, Tríceps, Pierna, Glúteo, Gemelos, Core, Antebrazo/Agarre.

## 3. equipment

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| name | TEXT | no vacío |
| normalized_name | TEXT UNIQUE | normalizado |
| is_active | INTEGER | default 1 |
| created_at | INTEGER | técnico |
| updated_at | INTEGER | técnico |

Seed: Mancuernas, Barra, Polea, Máquina, Discos, Banco, Peso corporal, Bandas elásticas, Kettlebell, Otro.

`Otro` no está protegido.

## 4. exercises

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID estable |
| name_es | TEXT | no vacío |
| normalized_name_es | TEXT UNIQUE | normalizado |
| name_en | TEXT | no vacío |
| normalized_name_en | TEXT UNIQUE | normalizado |
| muscle_group_id | TEXT FK | obligatorio |
| equipment_id | TEXT FK nullable | opcional |
| default_load_mode | TEXT | LoadMode |
| default_measurement_unit | TEXT | MeasurementUnit |
| rir_required | INTEGER | default 1 |
| initial_set_count | INTEGER nullable | >0 |
| initial_load | REAL nullable | >=0; null en BODYWEIGHT/NO_WEIGHT |
| initial_measurement | INTEGER nullable | >=0 |
| description | TEXT nullable | breve |
| is_favorite | INTEGER | default 0 |
| is_active | INTEGER | default 1 |
| created_at | INTEGER | técnico |
| updated_at | INTEGER | técnico |

Índices:

- unique `normalized_name_es`
- unique `normalized_name_en`
- index `muscle_group_id,is_active`
- index `is_favorite,is_active`

## 5. exercise_images

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| exercise_id | TEXT FK | cascade con borrado físico del ejercicio |
| position | INTEGER | 1..3, unique por exercise |
| source_type | TEXT | `SEED` / `USER` |
| storage_key | TEXT | ref lógica (`asset:*`/`user:*`) |
| original_source_url | TEXT nullable | auditoría licencia seed |
| author | TEXT nullable | seed |
| license | TEXT nullable | seed o `PROPIO` |

Máximo 3 imágenes validado en dominio y DB cuando sea viable.

## 6. routines

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| name | TEXT | no vacío; duplicados permitidos salvo decisión futura |
| suggested_session_type_id | TEXT FK nullable | opcional |
| description | TEXT nullable | no se copia a nota de sesión |
| created_at | INTEGER | técnico |
| updated_at | INTEGER | técnico |

## 7. routine_exercises

| Campo | Tipo | Regla |
|---|---|---|
| routine_id | TEXT FK | cascade al borrar rutina |
| exercise_id | TEXT FK | ejercicio maestro |
| position | INTEGER | orden |

PK/unique:

- PK compuesta `(routine_id, exercise_id)` → evita duplicado del mismo ejercicio.
- unique `(routine_id, position)`.

Un ejercicio desactivado puede seguir referenciado y se presenta marcado como desactivado.

## 8. sessions

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| session_date_epoch_day | INTEGER | obligatorio |
| order_in_day | INTEGER | >=1 |
| session_type_id | TEXT FK | referencia actual |
| session_type_name_snapshot | TEXT | obligatorio |
| name | TEXT | visible |
| is_auto_name | INTEGER | controla renombrado S<n> |
| general_note | TEXT nullable | opcional |
| operational_state | TEXT | SessionOperationalState |
| execution_result | TEXT | derivado/denormalizado |
| created_at | INTEGER | técnico |
| updated_at | INTEGER | técnico |

Constraints/índices:

- unique `(session_date_epoch_day, order_in_day)`.
- index `(session_date_epoch_day, operational_state)`.
- index `(session_date_epoch_day, execution_result)`.
- index `session_type_id`.

No existen horas de inicio/fin.

## 9. session_exercises

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| session_id | TEXT FK | cascade al borrar sesión |
| exercise_id | TEXT FK | referencia estable al maestro |
| position | INTEGER | orden dentro sesión |
| exercise_name_es_snapshot | TEXT | histórico |
| exercise_name_en_snapshot | TEXT | histórico |
| muscle_group_name_snapshot | TEXT | histórico |
| equipment_name_snapshot | TEXT nullable | histórico |
| default_load_mode_snapshot | TEXT | histórico |
| default_measurement_unit_snapshot | TEXT | histórico |
| rir_required_snapshot | INTEGER | histórico |
| description_snapshot | TEXT nullable | técnico/instrucción de instancia |
| exercise_rest_seconds | INTEGER nullable | default de descanso |
| note | TEXT nullable | nota de ejercicio |
| incomplete_reason | TEXT nullable | solo si no completado |
| is_finalized | INTEGER | estado de interacción, no cambia cálculo |

Constraints:

- unique `(session_id, exercise_id)` → no duplicados.
- unique `(session_id, position)`.

El estado `No realizado/Parcial/Completado` se calcula desde sus sets; no se almacena como override.

## 10. session_sets

| Campo | Tipo | Regla |
|---|---|---|
| id | TEXT PK | UUID |
| session_exercise_id | TEXT FK | cascade |
| position | INTEGER | >=1 |
| load_mode | TEXT | interpretación de carga de esta serie |
| measurement_unit | TEXT | interpretación de medición |
| target_load | REAL nullable | objetivo |
| actual_load | REAL nullable | real |
| target_measurement | INTEGER nullable | objetivo |
| actual_measurement | INTEGER nullable | real |
| rir | INTEGER nullable | 0..2 |
| rest_override_seconds | INTEGER nullable | >=0 |
| actual_confirmed | INTEGER | permite distinguir confirmación explícita cuando valores aplicables sean cero/vacíos |

Constraint:

- unique `(session_exercise_id, position)`.

### ¿Cuándo está realizada una serie?

`actual_confirmed = 1` **o** existe al menos un dato real aplicable introducido (`actual_load`, `actual_measurement`, `rir`).

`Cumplido` establece los reales copiables y `actual_confirmed = 1`.

Para series con RIR obligatorio, si la serie está realizada y `rir IS NULL`, no puede finalizarse la sesión.

## 11. Cálculos derivados

### Estado de ejercicio

Para N series planificadas:

- realizadas = 0 → `NOT_PERFORMED`
- realizadas >0 y <N → `PARTIAL`
- realizadas = N → `COMPLETED`

Si N=0, el ejercicio se considera `NOT_PERFORMED` hasta que tenga series; la UI debe evitar ejercicios sin series cuando exista un default razonable, pero el modelo lo tolera.

### Resultado de sesión

- ningún ejercicio con serie realizada → `NOT_STARTED`
- todos los ejercicios planificados `COMPLETED` y existe al menos uno → `COMPLETED`
- cualquier otro caso con al menos una serie realizada → `PARTIAL`

El campo `sessions.execution_result` se actualiza en la misma operación transaccional que altera datos reales.

## 12. Temporalidad para precarga

Comparación estricta:

```text
candidate.date < target.date
OR
(candidate.date == target.date AND candidate.order_in_day < target.order_in_day)
```

La referencia debe tener el ejercicio derivado `COMPLETED`.

No se usa una sesión posterior del mismo día.

## 13. Reglas de desactivación/borrado

### Tipo/grupo/equipo

Si existe referencia histórica: desactivar. Snapshots preservan nombre antiguo.

### Ejercicio

- con histórico → solo desactivar;
- sin histórico y sin rutina → borrado físico permitido;
- sin histórico pero en rutinas → confirmar y eliminar ejercicio + referencias de rutina atómicamente.

### Duplicar/crear desde rutina

Ejercicios desactivados se omiten de la nueva sesión y se avisa antes de confirmar creación.

## 14. Índices mínimos adicionales

- `session_exercises(exercise_id, session_id)` para histórico por ejercicio.
- `session_sets(session_exercise_id, position)` unique.
- `sessions(session_date_epoch_day, order_in_day)` unique.
- `routine_exercises(exercise_id)` para detectar referencias antes de borrado.

## 15. Room schema

Room exportará JSON de schema bajo `app/schemas/`. Todo PR que cambie DB debe incluir:

- versión incrementada;
- schema exportado;
- migración;
- tests de migración.
