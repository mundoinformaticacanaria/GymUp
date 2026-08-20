# ADR-005 — Circuito FUNCIONAL ↔ TECH LEAD para cierre del MVP v1

Estado: Aprobado

## Decisión

Las dudas funcionales pendientes del MVP v1 se centralizan en una issue de cierre funcional.

El circuito será:

1. La issue con label `FUNCIONAL` corresponde al funcional.
2. El funcional responde en la propia issue a todas las cuestiones pendientes que pueda resolver.
3. Cuando termina, cambia la label a `TECH LEAD`.
4. El Tech Lead recoge la issue, revisa coherencia con el contrato funcional, ADR y resto del repositorio, y actualiza la documentación aplicable.
5. Si detecta nuevas dudas, contradicciones, respuestas incompletas o decisiones que requieran producto, añade un nuevo comentario con las cuestiones concretas y devuelve la label a `FUNCIONAL`.
6. El ciclo se repite hasta que no queden dudas funcionales relevantes para el MVP v1.
7. El Tech Lead es responsable de mantener GitHub y el contrato funcional actualizados y de cerrar la issue cuando el MVP v1 quede funcionalmente cerrado.

## Fuente de verdad

GitHub es la fuente de verdad del proyecto. Las decisiones cerradas deben quedar consolidadas en `docs/PRODUCT_CONTRACT_V1.md` y, cuando proceda, en ADR específicos.

No se deben inferir decisiones funcionales no resueltas. Las contradicciones deben quedar explícitamente señaladas y resolverse antes de implementar el comportamiento afectado.

## Issue de trabajo

El circuito inicial se gestiona en la issue `#1 — [FUNCIONAL] Cierre de dudas pendientes — MVP v1`.
