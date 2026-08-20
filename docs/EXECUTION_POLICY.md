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
