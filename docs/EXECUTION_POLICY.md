# GymUp — Política de ejecución continua

Estado: **vigente**  
Fecha de entrada en vigor: 2026-08-20

Esta política complementa `docs/PROJECT_GOVERNANCE.md` y refleja la delegación operativa vigente del propietario.

## Regla principal

El Tech Lead debe continuar automáticamente con la siguiente tarea ejecutable del proyecto sin esperar una nueva orden del propietario.

El ciclo normal es:

1. localizar trabajo abierto que corresponda al Tech Lead;
2. ejecutar la Issue activa;
3. documentar decisiones duraderas;
4. implementar y verificar;
5. cerrar la Issue cuando cumpla sus criterios;
6. continuar inmediatamente con la siguiente Issue desbloqueada según dependencias/prioridad;
7. repetir hasta completar el MVP/release objetivo.

## Única condición de parada

El Tech Lead solo se detiene ante un **bloqueo real que requiera una acción exclusiva del propietario**, por ejemplo:

- una decisión funcional no resoluble desde el contrato;
- una credencial/secreto que solo el propietario puede facilitar;
- una acción física o en una cuenta externa no delegada;
- acceso/recurso que el Tech Lead no puede obtener;
- una decisión irreversible de producto reservada al propietario.

Cuando ocurra:

1. la Issue afectada debe quedar con label `PROPIETARIO`;
2. se añade un comentario que describa el bloqueo con precisión;
3. se indica exactamente qué dato/decisión/acción desbloquea el trabajo;
4. se continúa con cualquier otra Issue independiente que siga siendo ejecutable;
5. solo si no queda ninguna tarea independiente se considera el proyecto detenido esperando al propietario.

## Lo que NO es un bloqueo

No justifican detenerse:

- tener que elegir entre alternativas técnicas equivalentes;
- necesitar crear documentación, ADRs, tests o Issues;
- fallos de build/CI que puedan investigarse;
- necesidad de refactorizar;
- necesidad de buscar documentación pública;
- una Issue grande que pueda dividirse;
- ausencia de una orden explícita de “continúa”.

En esos casos el Tech Lead decide, documenta y sigue trabajando dentro de las restricciones funcionales y tecnológicas aprobadas.

## Fuente de verdad

El estado operativo debe poder conocerse desde GitHub:

- `TECH LEAD`: trabajo pendiente/activo del Tech Lead;
- `FUNCIONAL`: requiere intervención funcional cuando se abra de nuevo una decisión de producto;
- `PROPIETARIO`: requiere intervención exclusiva del propietario;
- Issue cerrada `completed`: trabajo aceptado como finalizado.

## Registro de cada sesión del Tech Lead en GitHub

Este protocolo se aplica a toda sesión que consulte GitHub para revisar, planificar, comentar, modificar o verificar una tarea. La excepción al requisito de rama/PR se limita a las dos entradas append-only del log.

1. Antes de consultar `main`, la cola, Issues, PR, ramas, CI o releases, relee únicamente `ops/logs/tech-lead.log`.
2. Si el último registro es un `INICIO` sin `FIN` posterior, no abras otra ejecución durante los primeros 30 minutos. Pasados 30 minutos, considera ese inicio huérfano: consérvalo y no fabriques un `FIN` retroactivo.
3. Registra un único `INICIO` en `ops/logs/tech-lead.log` en `main` antes de leer el resto de GitHub. Si no puedes registrarlo, detén la revisión.
4. Cuando terminen todas las operaciones de GitHub, añade un único `FIN` al mismo archivo. Debe ser la última operación de GitHub de esa sesión.

Formato:

```text
YYYY-MM-DDTHH:mm:ssZ | INICIO #<issue>: <tarea breve>
YYYY-MM-DDTHH:mm:ssZ | FIN #<issue>: <issues trabajadas y estado/resultados>
```

Las marcas de tiempo usan UTC (`Z`) y se toman inmediatamente antes de enviar la escritura al repositorio. Si no hay Issue se usa `SIN_ISSUE`; si no hubo actividad material, se usa `FIN NINGUNA`. El `FIN` enumera lo trabajado aunque quede pendiente e incluye, cuando corresponda, PR, resultado de CI, aprobación pendiente o bloqueo. No se permiten líneas de progreso intermedias, editar entradas antiguas ni cierres retroactivos. Una interrupción inesperada deja el `INICIO` abierto y visible.

El log es append-only y su escritura directa en `main` es una excepción explícita para trazabilidad operativa; no puede incluir cambios de código, producto o documentación. Los workflows ordinarios deben excluir cambios exclusivos del log para evitar compilar o publicar artefactos por el heartbeat.
