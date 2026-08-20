# ADR-001 — Gráficas históricas por ejercicio

Estado: Aprobado

## Decisión

La v1 mostrará la evolución histórica de cada ejercicio usando las últimas 10 ejecuciones válidas del ejercicio.

Una ejecución es válida para la gráfica cuando contiene **al menos una serie realizada**. Por tanto:

- `Parcial` se incluye;
- `Completado` se incluye;
- `No realizado` se excluye.

La gráfica utiliza exclusivamente datos **reales ejecutados**. Las series planificadas que quedaron pendientes no generan puntos ni valores en la gráfica.

Las ejecuciones `Parcial` deben ser distinguibles visualmente respecto de las `Completado`, mediante un indicador visible en el punto, en el detalle al seleccionarlo o mediante una solución equivalente que no induzca a interpretar una ejecución incompleta como completa.

No se incluye en v1 un filtro `Solo completadas`.

La gráfica tendrá selector entre:

- `Carga`
- `Medición`

`Medición` representa la unidad principal aplicable al ejercicio (por ejemplo repeticiones, repeticiones/lado, segundos o segundos/lado).

Cada serie se representa como una línea independiente.

La opción `Carga` se oculta cuando el ejercicio no tiene un valor numérico de carga aplicable.

Para modalidades `peso corporal + X kg` y `peso corporal - X kg asistencia`, la gráfica de carga representa únicamente el valor adicional `X`. Nunca se suma ni se infiere el peso corporal del usuario.

Las modalidades de carga son comparables únicamente consigo mismas:

- `kg/mano` con `kg/mano`;
- `kg/lado` con `kg/lado`;
- `kg total` con `kg total`;
- y de forma equivalente para el resto de modalidades numéricas aplicables.

Se representa el valor introducido tal cual. Nunca se convierten automáticamente modalidades de carga.

Si la modalidad cambia entre ejecuciones, la gráfica no une esos valores como una única progresión continua. Debe cortar la línea o representar una serie visual distinta por modalidad, dejando visible el cambio.

Ejemplo: `10 kg/mano → 12,5 kg/mano → 25 kg total` no puede mostrarse como una línea continua `10 → 12,5 → 25`.

No se calculan pesos efectivos derivados.
