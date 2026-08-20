# GymUp

GymUp es una aplicación Android offline para planificar, registrar y consultar sesiones de entrenamiento de gimnasio con el mínimo número de interacciones posible.

## Objetivo de producto

La v1 debe permitir:

- planificar sesiones en fechas pasadas, presentes o futuras;
- ejecutar una sesión registrando datos por serie;
- reutilizar rutinas maestras y duplicar listas de ejercicios de sesiones anteriores;
- consultar histórico de sesiones y evolución por ejercicio;
- generar un informe JSON por sesión realizada;
- exportar e importar una copia de seguridad completa de la aplicación;
- funcionar sin conexión a Internet.

La fuente de verdad funcional y técnica del proyecto es este repositorio.

## Documentación

- [Contrato funcional v1](docs/PRODUCT_CONTRACT_V1.md)
- [Cierre funcional MVP v1](docs/PRODUCT_CONTRACT_V1_CLOSURE.md)
- [Gobierno, roles y reglas de trabajo](docs/PROJECT_GOVERNANCE.md)

## Estado

**Contrato funcional del MVP v1 cerrado el 20/08/2026.**

La Issue #1 cerró el circuito FUNCIONAL ↔ TECH LEAD sin dudas funcionales relevantes pendientes. El catálogo inicial funcional queda aceptado con 61 ejercicios.

Fase actual: **definición de arquitectura técnica, modelo de datos, ADR iniciales y backlog de implementación**.

No deben implementarse funcionalidades que contradigan el contrato funcional vigente. Cualquier cambio futuro de producto debe quedar trazado explícitamente en GitHub antes de incorporarse al desarrollo.
