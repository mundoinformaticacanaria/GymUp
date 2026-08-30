# GymUp — Estrategia de pruebas v1

Estado: **vigente para implementación**  
Issue origen: #2

## Objetivo

Evitar regresiones en las reglas de entrenamiento y, especialmente, pérdida/corrupción de histórico. Las pruebas priorizan dominio, persistencia, import/export y flujos críticos sobre tests cosméticos.

## 1. Pirámide

### Unit tests JVM

Cobertura obligatoria para:

- normalización de nombres;
- saneado de nombres de archivo;
- cálculo de serie realizada;
- estado derivado de ejercicio;
- resultado derivado de sesión;
- validación de RIR;
- transición `PLANNED → IN_PROGRESS`;
- finalización de sesión;
- selección de precarga por fecha+orden;
- series adicionales copiando última serie de referencia;
- creación desde rutina y duplicación omitiendo desactivados;
- ranking de búsqueda;
- búsqueda y filtrado del selector de ejercicios de rutina;
- agrupación de datos de gráfica por modalidad/unidad;
- construcción de DTO de informe;
- validadores de backup.

### Tests instrumentados de Room

Cobertura obligatoria para:

- constraints de nombres normalizados;
- no duplicado de ejercicio por sesión/rutina;
- orden único por día/sesión/rutina;
- cascadas correctas al borrar sesión/rutina;
- baja lógica de maestros;
- queries temporales de precarga;
- filtros históricos combinados;
- actualización transaccional de resultado;
- guardado atómico de metadatos y orden completo de una rutina;
- migraciones Room.

### Tests UI Compose

Solo flujos críticos:

1. crear sesión vacía;
2. añadir ejercicio y series;
3. usar `Cumplido` y registrar RIR;
4. finalizar sesión parcial/completa;
5. continuar sesión En curso desde Home;
6. crear desde rutina con omitidos desactivados;
7. exportar una sesión realizada (con fake launcher/abstracción de destino);
8. cambio tema sistema/claro/oscuro;
9. texto grande sin perder controles esenciales.
10. crear y editar una rutina recorriendo Datos → Ejercicios → Resumen;
11. buscar, filtrar, añadir, reordenar y quitar ejercicios con la acción primaria visible.

## 2. Casos de regresión funcional obligatorios

### Sesiones

- finalizar sin ejecución → `REALIZED + NOT_STARTED`;
- una serie de tres → `PARTIAL`;
- todas las series → `COMPLETED`;
- edición posterior a `REALIZED` cambia resultado derivado pero no estado operativo;
- datos reales introducidos en `PLANNED` la pasan a `IN_PROGRESS`;
- objetivo precargado sin confirmar no cuenta como real.

### RIR

- RIR opcional puede quedar null;
- RIR obligatorio bloquea finalización solo en series realizadas;
- RIR 0 es dato válido;
- `Cumplido` nunca rellena RIR.

### Precarga

- solo último `COMPLETED` anterior;
- ignora `PARTIAL`;
- respeta orden de dos sesiones del mismo día;
- nunca mira al futuro;
- sin histórico usa master;
- sin master queda vacío;
- serie N+1 copia última serie de referencia.

### Histórico/gráficas

- `PARTIAL` con al menos una serie entra en últimas 10;
- `NOT_PERFORMED` queda fuera;
- solo datos reales generan puntos;
- `KG_PER_HAND` no conecta con `KG_TOTAL`;
- segundos no conectan con repeticiones;
- falta de serie en una ejecución crea hueco, no cero.

### Maestros

- nombres iguales por mayúsculas/tildes chocan;
- `Otro` de Tipo de sesión no se modifica;
- ejercicio con histórico se desactiva, no se borra;
- ejercicio sin histórico en rutina requiere operación atómica sobre referencias.

### Rutinas y ergonomía

- listado, creación y edición son destinos independientes;
- el borrador no se persiste parcialmente antes de `Guardar`;
- el guardado conserva exactamente el orden visible;
- no se puede guardar el mismo ejercicio dos veces;
- un ejercicio desactivado ya presente puede conservarse o quitarse, pero no añadirse;
- `Siguiente`, `Anterior` y `Guardar` permanecen localizables sin recorrer la lista;
- las listas largas desplazan su contenido dentro de una zona acotada;
- con fuente ampliada ningún control esencial queda oculto.

### Export/import

- informe no permitido si sesión no `REALIZED`;
- informe separa target/actual;
- backup corrupto no modifica DB;
- checksum incorrecto rechaza importación;
- versión incompatible rechaza importación;
- import válida reemplaza todo;
- IDs/snapshots sobreviven round-trip export→import;
- path traversal en ZIP se rechaza.

## 3. CI

### Verificación habitual

Cada pull request con cambios de código ejecuta una única verificación. También se verifica cada actualización de `main` con cambios de código. Los cambios exclusivamente documentales quedan excluidos y una ejecución nueva cancela la anterior de la misma PR.

El job ejecuta en una sola invocación:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

La verificación habitual no ejecuta `assembleRelease` ni publica APK.

### Candidatos de prueba

El APK release solo se genera mediante el workflow explícito `Android Candidate`, activado manualmente o con una etiqueta `candidate-*` sobre el commit exacto. Ese workflow ejecuta:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleRelease
```

Solo publica el artefacto `gymup-v1-release`, con 7 días de retención. La política completa está en `docs/CI_USAGE_POLICY.md`.

Cuando existan tests instrumentados, se evaluará su ejecución en candidatos o mediante Gradle Managed Device. No se incorporarán a cada PR sin medir antes su tiempo y consumo.

## 4. Release gate MVP

No se genera APK final si falla cualquiera de:

- unit tests;
- lint con errores;
- build release/debug aplicable;
- tests de migración/importación;
- checklist manual del flujo principal.

## 5. Datos de prueba

Usar fixtures pequeños y deterministas. No incluir backups/datos personales reales del propietario en el repositorio.

## 6. Calidad de tests

- Evitar sleeps; usar dispatchers/test clocks.
- IDs/fechas fijos en tests.
- Cada bug funcional corregido añade test de regresión cuando sea reproducible.
- Tests no deben depender de Internet.

