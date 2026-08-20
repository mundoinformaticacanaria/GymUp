# ADR-004 — Unicidad de nombres de ejercicios

Estado: Aprobado

## Decisión

En GymUp v1 el catálogo maestro de ejercicios no permitirá nombres duplicados.

La unicidad debe aplicarse a:

- `nombre_es`
- `nombre_en`

La comparación de unicidad debe seguir la misma normalización básica usada por la búsqueda: ignorar mayúsculas/minúsculas y tildes/diacríticos.

Por tanto, variantes como `Press`, `PRESS` o `PrÉss` deben considerarse el mismo nombre a efectos de validación.

El objetivo es evitar duplicados semánticos y mantener limpio el catálogo maestro.
