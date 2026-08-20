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

Para `kg/mano`, `kg/lado` y `kg total`, se representa el valor introducido tal cual, pero solo se compara dentro de la misma modalidad.

Reglas de comparabilidad de carga:

- `kg/mano` solo se compara con `kg/mano`;
- `kg/lado` solo se compara con `kg/lado`;
- `kg total` solo se compara con `kg total`;
- no se realizan conversiones automáticas entre modalidades;
- si un ejercicio cambia de modalidad entre sesiones, la gráfica debe cortar la línea o representar una serie diferenciada para hacer visible el cambio de modalidad;
- no debe dibujarse una línea continua entre valores expresados en modalidades distintas.

Ejemplo: `10 kg/mano → 12,5 kg/mano → 25 kg total` no debe representarse como una progresión continua `10 → 12,5 → 25`.

No se calculan pesos efectivos derivados.
