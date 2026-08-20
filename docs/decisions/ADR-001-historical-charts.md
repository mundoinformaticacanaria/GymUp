# ADR-001 — Gráficas históricas por ejercicio

Estado: Aprobado

## Decisión

La v1 mostrará la evolución histórica de cada ejercicio usando las últimas 10 ejecuciones del ejercicio.

La gráfica tendrá selector entre:

- `Carga`
- `Medición`

`Medición` representa la unidad principal aplicable al ejercicio (por ejemplo repeticiones, repeticiones/lado, segundos o segundos/lado).

Cada serie se representa como una línea independiente.

La opción `Carga` se oculta cuando el ejercicio no tiene un valor numérico de carga aplicable.

Para modalidades `peso corporal + X kg` y `peso corporal - X kg asistencia`, la gráfica de carga representa únicamente el valor adicional `X`. Nunca se suma ni se infiere el peso corporal del usuario.

No se calculan pesos efectivos derivados.
