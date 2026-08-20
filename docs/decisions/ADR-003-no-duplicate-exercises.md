# ADR-003 — No duplicar ejercicios dentro de una sesión o rutina

Estado: Aprobado

## Decisión

En GymUp v1, un mismo ejercicio maestro no puede aparecer más de una vez dentro de una misma sesión.

La misma regla se aplica a las rutinas: un mismo ejercicio maestro no puede aparecer más de una vez dentro de una misma rutina.

Si el usuario intenta añadir un ejercicio ya presente, la app debe impedir la duplicación y dirigir la interacción hacia la instancia ya existente.

Esta restricción se aplica por identidad del ejercicio maestro, no por el texto visible del nombre.
