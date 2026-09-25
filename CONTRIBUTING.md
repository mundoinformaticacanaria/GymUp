# Contribuir a GymUp

## Fuente de verdad

Antes de trabajar, sigue primero `AGENTS.md` y registra la sesión conforme a `docs/EXECUTION_POLICY.md`. Después lee `README.md`, `docs/PROJECT_GOVERNANCE.md`, el contrato funcional y la Issue asignada. Las decisiones de producto deben quedar en GitHub antes de implementarse.

## Flujo de trabajo

1. Comprueba Issues, ramas y PR abiertas para evitar duplicar trabajo.
2. Trabaja sobre una única Issue en una rama propia y con alcance acotado.
3. No cambies comportamiento visible que no esté aprobado en la Issue o en el contrato funcional.
4. Añade o actualiza pruebas cuando cambien reglas, persistencia o un defecto reproducible.
5. Abre una PR que enlace la Issue y explique alcance, base, validaciones y riesgos.
6. Mantén separadas las PR apiladas e indica expresamente su dependencia.
7. Tras la revisión técnica y la CI, el Tech Lead puede fusionar si se cumplen los criterios y no se activa consumo facturable/cuota limitada de GitHub ni uso de tokens facturados; si se activa o no puede verificarse el coste, solicita autorización al Product Owner.

No se escriben cambios de producto, código o documentación directamente en `main`. La única excepción es añadir las entradas `INICIO` y `FIN` a `ops/logs/tech-lead.log`, de acuerdo con la política vigente; esas entradas deben quedar en commits separados.

## Validación

Para una PR con código Android deben quedar correctos:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Android CI es la verificación compartida. Una PR solo documental puede quedar excluida por los filtros del workflow. No se debe afirmar que existen pruebas instrumentadas o Compose si la comprobación pertenece a JVM/Robolectric o a validación física.

## Integración y candidatos

- CI correcto no basta: deben cumplirse también aceptación, revisión y dependencias.
- No se pide aprobación solo para fusionar; se pide si el merge activa consumo facturable/cuota limitada de GitHub, tokens facturados o un coste incierto.
- No se genera un APK por cada corrección.
- Los candidatos se acumulan por bloque funcional en la Issue #35.
- Antes de un candidato se enumera su contenido exacto respecto al anterior y se solicita autorización expresa.
- Solo el workflow de candidato puede publicar el APK temporal.
- Commit, etiqueta y SHA-256 del APK deben quedar trazados.
- La prueba física del propietario no se sustituye por CI.

## Datos y seguridad

No subas secretos, credenciales, backups personales, informes reales, notas del usuario ni datos de prueba que permitan identificar a una persona.
