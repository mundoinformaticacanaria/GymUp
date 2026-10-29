# GymUp — Auditoría de crecimiento vertical v1

Estado: **vigente para backlog UX**

Issue origen: #18

Fecha: 2026-08-30

## Criterio

El objetivo no es prohibir el scroll. Las listas dinámicas y la fuente ampliada necesitan desplazamiento. La auditoría distingue entre:

- **lista acotada**: el contenido dinámico desplaza dentro de una zona clara;
- **formulario progresivo**: una tarea larga se divide en pasos o secciones;
- **acumulación problemática**: responsabilidades y acciones distintas se añaden una debajo de otra y la acción principal puede salir del viewport.

Una pantalla se considera aceptable cuando su acción principal o siguiente paso permanece localizable y el usuario entiende que una lista continúa.

## Resultado por pantalla

| Flujo | Implementación observada | Riesgo | Decisión |
|---|---|---:|---|
| Home | Una `LazyColumn` contiene sesiones de hoy, nueva sesión y cinco accesos rápidos | Alto cuando hay varias sesiones: la navegación queda debajo de contenido dinámico | Rediseñar jerarquía y mantener navegación principal accesible |
| Sesiones | `Nueva sesión` fija sobre una `LazyColumn` de sesiones | Bajo | Mantener; el scroll corresponde a una lista dinámica |
| Nueva sesión | Formulario desplazable con CTA `Crear sesión` en `bottomBar` | Bajo | Mantener; la acción primaria ya es persistente |
| Detalle de sesión | Metadatos completos, buscador y ejercicios comparten la columna principal; el ejercicio seleccionado ya usa una vista propia | Alto | Separar resumen/metadatos de la lista y mantener la acción operativa visible |
| Rutinas | Listado y editor se acumulaban en una sola columna | Alto | Resuelto por el flujo de `ROUTINES_UX_FLOW_V1.md` |
| Ejercicios | Alta, búsqueda y filtros permanecen sobre una lista acotada | Bajo | Mantener |
| Editor de ejercicio | Todos los campos, catálogos, defaults, descripción, guardar y baja se apilan | Alto | Dividir en pasos/secciones y fijar Guardar |
| Histórico de ejercicio | Resumen y ejecuciones históricas forman una lista de consulta | Bajo | Mantener; el scroll representa histórico dinámico |
| Histórico de sesiones | El bloque de filtros completo precede siempre a los resultados | Medio | Replegar filtros y mostrar estado/resumen de filtros junto a la lista |
| Ajustes | Tema, catálogos, backup, limpieza y Volver comparten una única lista | Alto | Convertir Ajustes en índice y abrir responsabilidades independientes |
| Catálogos | Tres catálogos completos se muestran secuencialmente | Medio | Mostrar un catálogo cada vez; conservar altas/ediciones en diálogo |

## Orden de implementación

1. Rutinas: mayor fricción declarada por el propietario y causa verificada de acciones aparentemente inertes.
2. Detalle de sesión: flujo operativo principal durante el entrenamiento.
3. Editor de ejercicio: formulario largo con acción Guardar al final.
4. Ajustes y catálogos: responsabilidades independientes hoy acumuladas.
5. Home: hacer estable la navegación sin ocultar las sesiones prioritarias de hoy.
6. Histórico de sesiones: reducir el bloque de filtros sin limitar combinaciones.

## Reglas transversales de aceptación

- Una responsabilidad principal por destino.
- CTA primaria persistente en formularios y asistentes.
- Las listas usan el espacio restante de la pantalla y conservan scroll.
- Los filtros no desplazan permanentemente los resultados fuera del primer viewport.
- La fuente ampliada puede activar scroll sin ocultar controles esenciales.
- Ningún rediseño cambia reglas de negocio, datos o capacidades sin una decisión funcional trazada.
- Cada bloque se valida físicamente en orientación vertical con el APK exacto de su PR.
