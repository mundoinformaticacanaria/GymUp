# GymUp — Cierre funcional MVP v1

Fecha de cierre: **20/08/2026**

Estado: **CERRADO FUNCIONALMENTE**

## Alcance del cierre

La definición funcional del MVP v1 de GymUp queda cerrada tras completar el circuito de revisión de la Issue #1.

Se consideran aceptadas y consolidadas las decisiones F-01 a F-14.1, incluyendo:

- búsqueda y prioridad de ejercicios;
- reglas de precarga histórica;
- comparabilidad de carga y medición;
- catálogos iniciales;
- estados derivados de ejercicio y sesión;
- cambios posteriores de fecha/orden;
- comportamiento de ejercicios desactivados;
- unicidad y protección de valores maestros;
- backup ZIP sin cifrado propio en v1;
- catálogo funcional inicial de **61 ejercicios**.

## Catálogo inicial

El dataset funcional definitivo es el entregado por FUNCIONAL en la respuesta **F-14.1** de la Issue #1 e incorpora como ejercicio nº 61:

- `Elevación lateral en máquina`
- `Machine Lateral Raise`
- grupo `Hombro`
- equipo `Máquina`
- modalidad `KG_POR_LADO`
- unidad `REPETICIONES`
- `rir_obligatorio = true`

El Tech Lead ha validado el dataset contra `docs/data/EXERCISE_CATALOG_TEMPLATE.md` y las decisiones funcionales vigentes.

La transformación de este dataset a un recurso seed ejecutable de Android pertenece a la fase de implementación y no modifica su contenido funcional sin una decisión explícita posterior.

## Fuente de verdad

El contrato funcional detallado sigue siendo `docs/PRODUCT_CONTRACT_V1.md`, complementado por los ADR aprobados y por este acta de cierre.

La Issue #1 queda cerrada como `completed`.

A partir de este punto, cualquier cambio de comportamiento visible o de reglas de negocio debe tratarse como cambio funcional nuevo y quedar trazado en GitHub antes de implementarse.

## Siguiente fase

El proyecto pasa a definición técnica e implementación:

1. arquitectura Android y stack;
2. modelo de datos y snapshots históricos;
3. ADR técnicos iniciales;
4. esquema JSON de informes y backup;
5. backlog priorizado;
6. esqueleto del proyecto y CI;
7. implementación por Issues.
