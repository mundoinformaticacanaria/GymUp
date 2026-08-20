# GymUp — Registro de decisiones de producto

Este documento registra decisiones cerradas durante la definición de GymUp v1. Cuando una decisión quede consolidada, debe reflejarse también en el contrato funcional principal si corresponde.

## 2026-08-20 — Gráfica histórica por ejercicio

La gráfica histórica no debe asumir siempre peso + repeticiones.

Se generaliza a dos vistas:

- `Carga`
- `Medición`

Reglas:

- `Medición` representa la unidad principal del ejercicio: repeticiones, repeticiones/lado, segundos o segundos/lado.
- `Carga` representa el valor numérico de carga cuando exista.
- Si el ejercicio no tiene una carga numérica representable, la vista `Carga` se oculta.
- La gráfica muestra las últimas 10 ejecuciones del ejercicio.
- Cada serie se representa como una línea independiente.
