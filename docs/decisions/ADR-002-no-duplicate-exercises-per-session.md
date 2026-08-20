# ADR-002 — Evitar ejercicios duplicados dentro de una sesión

Estado: Aprobado

## Decisión

En GymUp v1, un mismo ejercicio maestro no puede aparecer más de una vez dentro de la misma sesión.

Si el usuario intenta añadir un ejercicio que ya está presente en esa sesión, la aplicación debe impedir la duplicación y dirigirle o señalarle la instancia existente.

Esta regla se aplica independientemente del origen de la sesión:

- sesión vacía;
- desde rutina;
- duplicar sesión;
- edición posterior de la sesión.

La restricción afecta al ejercicio maestro identificado internamente, no al texto visible del nombre, que puede editarse dentro de la instancia de sesión.
