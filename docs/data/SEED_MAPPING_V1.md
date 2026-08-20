# GymUp — Mapeo del seed funcional v1

Estado: **vigente para implementación**  
Issue origen: #2

El catálogo funcional definitivo de 61 ejercicios fue aprobado en la Issue #1 usando los identificadores de enum de la plantilla funcional original. La implementación Kotlin utiliza nombres internos en inglés para mantener consistencia con el código.

**No se modifica ni se reinterpreta el dataset funcional aceptado.** El loader de seed aplica un mapeo determinista y cubierto por tests.

## Modalidad de carga

| Seed funcional | Dominio Kotlin |
|---|---|
| `KG_TOTAL` | `KG_TOTAL` |
| `KG_POR_MANO` | `KG_PER_HAND` |
| `KG_POR_LADO` | `KG_PER_SIDE` |
| `PESO_CORPORAL` | `BODYWEIGHT` |
| `PESO_CORPORAL_LASTRE` | `BODYWEIGHT_PLUS_LOAD` |
| `PESO_CORPORAL_ASISTENCIA` | `BODYWEIGHT_MINUS_ASSISTANCE` |
| `SIN_PESO` | `NO_WEIGHT` |

## Unidad de medición

| Seed funcional | Dominio Kotlin |
|---|---|
| `REPETICIONES` | `REPETITIONS` |
| `REPETICIONES_LADO` | `REPETITIONS_PER_SIDE` |
| `SEGUNDOS` | `SECONDS` |
| `SEGUNDOS_LADO` | `SECONDS_PER_SIDE` |

## Reglas

1. Un valor desconocido hace fallar la carga del seed durante desarrollo/test; nunca se degrada a un valor por defecto.
2. El mapeo se prueba exhaustivamente para todos los valores permitidos.
3. Los JSON externos versionados de GymUp (`session-report`, `backup`) usan los enums técnicos documentados en sus schemas; el seed funcional es un artefacto de entrada distinto y no define el contrato de exportación.
4. Si en el futuro cambia un enum externo versionado, se hará mediante nueva `schema_version`/migrador, no cambiando silenciosamente el significado de v1.
5. El seed ejecutable que se empaquete en `app` se genera/valida a partir del catálogo funcional aceptado y debe contener exactamente los mismos 61 ejercicios y semántica.
