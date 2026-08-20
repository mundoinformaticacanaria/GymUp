# GymUp — Contrato funcional v1

Estado: **vivo / en definición**

Este documento recoge las decisiones de producto cerradas para GymUp v1. Si una conversación, issue o implementación contradice este documento, debe detenerse el trabajo y resolver la discrepancia antes de continuar.

## 1. Alcance general

GymUp v1 es una aplicación Android monousuario, offline, orientada a móvil en vertical, para:

- planificar sesiones de entrenamiento;
- ejecutar y registrar sesiones;
- mantener catálogo de ejercicios y datos maestros relacionados;
- reutilizar rutinas;
- consultar histórico;
- visualizar evolución por ejercicio;
- generar informes JSON por sesión realizada;
- exportar/importar una copia completa de seguridad.

Distribución inicial: APK instalada manualmente.

Compatibilidad mínima: Android 15. Dispositivo principal objetivo: Android 16.

Interfaz: español. Los ejercicios muestran nombre en español e inglés.

## 2. Principios de producto

- La aplicación debe reducir fricción y clics durante el entrenamiento.
- Los defaults deben ser fuertes, pero nunca rígidos.
- El histórico debe conservar el contexto con el que fue registrado.
- Los datos maestros evolucionan; el histórico no debe reescribirse automáticamente por cambios posteriores.
- No se bloquea la edición de sesiones por haber sido finalizadas.
- No se deben inferir datos de ejecución que el usuario no haya confirmado.
- Toda funcionalidad principal debe funcionar sin conexión a Internet.

## 3. Sesiones

### 3.1 Identidad y orden

Una sesión tiene, como mínimo:

- identificador interno propio;
- fecha;
- `orden_en_dia` numérico;
- tipo de sesión;
- nombre visible;
- nota general opcional;
- estado operativo;
- resultado de ejecución.

No existen `hora_inicio` ni `hora_fin` en v1.

Se permiten múltiples sesiones en una misma fecha.

`orden_en_dia` sirve para ordenar sesiones de la misma fecha y puede modificarse manualmente. No debe depender del ID técnico ni del nombre visible.

El nombre automático usa:

`<Día de semana> <dd/mm/aaaa> S<n>`

Ejemplo:

`Martes 18/08/2026 S1`

El nombre es opcional para el usuario, autogenerado si queda vacío y siempre editable.

Si el nombre sigue siendo autogenerado y cambia `orden_en_dia`, el nombre se actualiza automáticamente. Si fue personalizado, se respeta.

### 3.2 Estados

Estado operativo:

- `Planificada`
- `En curso`
- `Realizada`

Reglas:

- una sesión planificada pasa automáticamente a `En curso` cuando comienzan a registrarse datos reales;
- solo pasa a `Realizada` cuando el usuario pulsa `Finalizar sesión`;
- puede finalizarse con ejercicios o series sin completar;
- editar posteriormente una sesión `Realizada` no cambia su estado;
- cualquier cambio desde `Realizada` a otro estado debe ser manual.

Resultado de ejecución:

- `No iniciada`
- `Parcial`
- `Completada`

Este resultado se deriva automáticamente del estado real de los ejercicios y se recalcula cuando cambian datos de ejecución, incluso en sesiones ya `Realizada`.

Estado operativo y resultado son conceptos independientes.

Ejemplos válidos:

- `Planificada + No iniciada`
- `En curso + Parcial`
- `Realizada + Parcial`
- `Realizada + Completada`

### 3.3 Creación

Al pulsar `Nueva sesión` se elige primero uno de estos orígenes:

1. `Desde rutina`
2. `Duplicar sesión`
3. `Sesión vacía`

Después se muestra una pantalla común breve:

- Fecha: obligatoria, precargada con hoy y editable a pasado o futuro.
- Tipo de sesión: obligatorio.
- Nombre: opcional, autogenerado si queda vacío.
- Nota general: opcional.

`Duplicar sesión` copia únicamente la lista de ejercicios y su orden. No copia objetivos ni datos reales. El tipo de sesión se hereda como valor sugerido, pero sigue siendo editable.

`Desde rutina` copia únicamente la lista ordenada de ejercicios. El tipo sugerido de la rutina, si existe, se precarga y sigue siendo editable.

### 3.4 Edición y borrado

Una sesión puede editarse siempre, antes, durante o después de finalizarse.

Los ejercicios de una sesión pueden reordenarse antes, durante y después.

Se permite eliminar cualquier sesión, incluida una realizada, con confirmación previa.

No existe archivado en v1.

## 4. Ejercicios dentro de una sesión

### 4.1 Independencia respecto al catálogo

Al añadir un ejercicio a una sesión se crea una instancia propia de esa sesión.

La instancia guarda snapshot de los datos necesarios para interpretar el histórico, incluyendo al menos:

- nombre español;
- nombre inglés;
- grupo muscular mostrado;
- equipo mostrado si existe;
- modalidad de carga;
- unidad de medición;
- demás defaults necesarios para interpretar la sesión.

Editar una instancia dentro de una sesión solo modifica esa instancia.

Editar el catálogo maestro solo modifica defaults para usos futuros.

### 4.2 Series

Cada ejercicio tiene un número de series.

Ese número se precarga desde la última ejecución completada válida o, si no existe, desde objetivos iniciales opcionales de la ficha maestra.

Dentro de la sesión se pueden añadir o eliminar series libremente.

Cada serie es editable de forma independiente.

La presentación funcional debe separar claramente:

- objetivo;
- real.

Como mínimo, por serie se contempla:

- peso objetivo;
- peso real;
- medición objetivo;
- medición real;
- RIR;
- descanso aplicable.

Existe una acción rápida `Cumplido` que copia a `Real` el peso objetivo y la medición objetivo. Los valores resultantes siguen siendo editables.

`Cumplido` nunca autocompleta RIR.

### 4.3 Estado de serie

Una serie puede estar en:

- `Pendiente`: no tiene ningún dato real confirmado de la sesión actual.
- `Realizada`: tiene al menos un dato real de ejecución aplicable.

Los valores precargados de objetivo no cuentan como datos reales mientras no se confirmen o introduzcan como datos de la sesión actual.

### 4.4 Estado de ejercicio

El ejercicio puede estar en:

- `No realizado`
- `Parcial`
- `Completado`

Regla automática al finalizar el ejercicio o la sesión:

- 0 series realizadas → `No realizado`;
- algunas series realizadas y alguna pendiente → `Parcial`;
- todas las series realizadas → `Completado`.

El ejercicio puede finalizarse manualmente en cualquier momento. Los ejercicios que sigan abiertos se finalizan automáticamente al finalizar la sesión.

El estado calculado puede modificarse manualmente después.

Si un ejercicio no queda completado, puede registrarse un motivo opcional a nivel de ejercicio, por ejemplo:

- máquina ocupada;
- falta de tiempo;
- molestia;
- fatiga;
- decisión de la sesión;
- otro.

No se requiere motivo por serie.

## 5. Precarga de objetivos

Para una sesión con fecha y orden determinados, la precarga de un ejercicio busca la referencia temporalmente coherente.

Prioridad:

1. última ejecución `Completada` de ese ejercicio anterior a la sesión actual;
2. si no existe, objetivos iniciales opcionales de la ficha maestra;
3. si tampoco existen, campos vacíos.

Solo las ejecuciones `Completadas` sirven para precarga. Se ignoran `Parcial` y `No realizado`.

En el mismo día, una sesión posterior puede usar una ejecución válida de una sesión anterior del mismo día según `orden_en_dia`.

Nunca debe usarse una ejecución posterior en el tiempo para precargar una sesión pasada.

Objetivos iniciales de ficha maestra:

- número de series opcional;
- carga inicial opcional;
- objetivo inicial de medición opcional.

Existe un único objetivo inicial común que se replica en todas las series iniciales.

## 6. Carga

Modalidades de carga soportadas en v1:

- `kg total`
- `kg/mano`
- `kg/lado`
- `peso corporal`
- `peso corporal + X kg`
- `peso corporal - X kg asistencia`
- `sin peso`

Se guarda la modalidad indicada; no se calcula un peso total efectivo derivado.

Peso en kg: hasta 2 decimales.

La interfaz usa coma decimal en español.

## 7. Medición de la serie

Unidades de trabajo principales soportadas en v1:

- repeticiones;
- repeticiones/lado;
- segundos;
- segundos/lado.

Repeticiones y segundos son enteros.

`Distancia` queda prevista para evolución futura, pero fuera de la interfaz v1.

## 8. RIR

RIR no es obligatorio a nivel de base de datos.

Cada ejercicio tiene `RIR obligatorio`, activado por defecto y editable.

Valores permitidos en v1:

- `0`
- `1`
- `2`

La interfaz usa tres botones rápidos `0 / 1 / 2`.

Si `RIR obligatorio` está desactivado, no se solicita ni se muestra aviso obligatorio.

Si está activado y una serie tiene datos reales, no puede quedar sin RIR al validar/finalizar. La aplicación debe solicitarlo mediante modal.

Al finalizar una sesión, si existen varias series realizadas con RIR obligatorio pendiente, se solicitan antes de permitir finalizar.

Las series pendientes no requieren RIR.

Cambiar un ejercicio histórico de RIR opcional a obligatorio no invalida RIR vacíos ya existentes.

## 9. Descanso

El descanso se introduce manualmente.

Puede definirse:

- por ejercicio, aplicándose por defecto a sus series;
- por serie, permitiendo excepción individual.

Se puede introducir en segundos o minutos.

Segundos es la unidad por defecto.

Internamente puede normalizarse a segundos.

## 10. Notas

- Nota general de sesión: opcional.
- Nota por ejercicio: opcional y necesaria para registrar molestias, fatiga, incidencias u observaciones.
- No se requiere nota por serie en v1.

## 11. Catálogo maestro de ejercicios

La v1 debe arrancar con un catálogo curado, no gigantesco, aproximadamente 60–100 ejercicios aportados como datos iniciales.

Cada ejercicio maestro contempla como mínimo:

- `nombre_es`;
- `nombre_en`;
- grupo muscular principal;
- equipo opcional;
- modalidad de carga por defecto;
- unidad de medición por defecto;
- RIR obligatorio sí/no;
- objetivos iniciales opcionales;
- descripción/instrucciones breves opcionales;
- entre 1 y 3 imágenes estáticas;
- favorito sí/no;
- estado activo/inactivo si tiene histórico.

No se duplican ejercicios por idioma: español e inglés son atributos del mismo ejercicio.

El formato visual de nombres es:

`Nombre español · English name`

con el mismo peso visual.

Los ejercicios personalizados pueden crearse y pueden añadir 1–3 imágenes desde la galería.

No se usa cámara en v1.

Durante una sesión deben poder abrirse imágenes e instrucciones técnicas sin abandonar el registro.

### 11.1 Favoritos

Los favoritos los marca y desmarca explícitamente el usuario. No se infieren automáticamente por frecuencia.

### 11.2 Baja de ejercicios

Si un ejercicio tiene histórico, eliminarlo desde mantenimiento significa baja lógica/desactivación:

- deja de aparecer para nuevas sesiones;
- deja de aparecer en búsquedas normales;
- conserva intacto el histórico.

Solo ejercicios sin histórico pueden borrarse definitivamente desde gestión normal.

## 12. Búsqueda de ejercicios

La búsqueda es incremental y se actualiza con cada carácter; no requiere botón.

Busca solo en `nombre_es` y `nombre_en` en v1.

Normalización:

- insensible a mayúsculas/minúsculas;
- ignora tildes/diacríticos.

Orden de coincidencia textual:

1. ejercicios cuyo nombre empiece por el texto buscado;
2. ejercicios cuyo nombre contenga la secuencia;
3. sin duplicar resultados ya listados.

No existe tolerancia a errores tipográficos en v1.

Filtro mínimo disponible: grupo muscular.

Filtro por equipo: fuera de v1.

Filtro por patrón de movimiento: fuera de v1.

## 13. Grupos musculares

Cada ejercicio tiene un único grupo muscular principal en v1.

Grupo muscular es catálogo editable con lista inicial.

Valores iniciales propuestos:

- Pecho
- Espalda
- Hombro
- Bíceps
- Tríceps
- Pierna
- Glúteo
- Gemelos
- Core
- Antebrazo/Agarre

Se permite:

- crear;
- renombrar;
- desactivar.

Si existe histórico, los cambios del maestro no reescriben sesiones antiguas.

## 14. Equipo

Equipo es catálogo editable, opcional en la ficha de ejercicio.

Valores iniciales propuestos:

- Mancuernas
- Barra
- Polea
- Máquina
- Discos
- Banco
- Peso corporal
- Bandas elásticas
- Kettlebell
- Otro

Se permite crear, renombrar y desactivar.

El histórico conserva el valor existente en el momento de la sesión.

## 15. Patrón de movimiento

Fuera de v1.

Debe poder añadirse en una versión futura sin romper el modelo existente. Candidato futuro: catálogo editable.

## 16. Tipos de sesión

Tipo de sesión es catálogo editable y obligatorio al crear una sesión.

Debe ofrecer selección rápida, recordar el último tipo usado cuando tenga sentido y disponer de `Otro` como opción de escape.

Si un tipo usado históricamente se elimina del maestro, las sesiones antiguas lo conservan y deja de estar disponible para nuevas sesiones.

El listado inicial será aportado posteriormente.

## 17. Rutinas

Una rutina es una plantilla maestra sin vínculo posterior con las sesiones que crea.

Campos v1:

- nombre;
- tipo de sesión sugerido opcional;
- descripción/nota opcional;
- lista ordenada de ejercicios.

No contiene objetivos propios de peso, series o repeticiones.

Al crear una sesión desde rutina:

- se copian ejercicios + orden;
- cada ejercicio calcula objetivos por histórico/defaults;
- el tipo sugerido se precarga si existe, pero sigue siendo editable;
- la descripción de la rutina no se copia a la nota de sesión.

Las rutinas pueden editarse o eliminarse.

No existe activación/desactivación de rutinas en v1.

Eliminar o editar una rutina nunca modifica sesiones ya creadas.

## 18. Histórico de sesiones

El histórico permite filtrar simultáneamente por:

- estado operativo;
- resultado de ejecución;
- tipo de sesión;
- rango exacto de fechas.

Los filtros pueden combinarse.

Los filtros no se persisten entre aperturas de la pantalla en v1.

## 19. Histórico por ejercicio y gráfica

Al consultar un ejercicio se muestran sus ejecuciones históricas.

La gráfica de evolución usa las últimas 10 ejecuciones de ese ejercicio.

Selector de métrica:

- Peso
- Repeticiones

Cada serie se representa como línea independiente.

No se incluyen en v1 métricas derivadas como volumen total o 1RM estimado.

## 20. Informes por sesión

Solo las sesiones `Realizada` pueden generar informe.

Se genera un archivo JSON independiente por sesión.

El JSON debe ser estructurado, versionado y apto para lectura/explotación posterior por ChatGPT u otros procesos.

Incluye al menos:

Datos generales:

- fecha;
- nombre;
- tipo de sesión;
- estado operativo;
- resultado de ejecución;
- nota general.

Detalle:

- ejercicios planificados;
- estado de cada ejercicio;
- series planificadas;
- series realizadas;
- carga prevista y real;
- medición prevista y real;
- RIR cuando exista;
- descansos;
- notas/incidencias;
- motivo opcional si no se completó.

Debe diferenciar claramente planificado vs ejecutado.

Cualquier métrica futura de volumen/carga/repeticiones debe contar solo datos realmente ejecutados.

El archivo puede:

- guardarse en almacenamiento mediante selector estándar de Android;
- compartirse mediante el menú estándar de Android.

Nombre del archivo: fecha + nombre visible de sesión, saneando caracteres técnicamente inválidos.

## 21. Copia de seguridad e importación

Los datos se almacenan localmente.

No existe sincronización entre dispositivos en v1.

La app permite:

- exportación completa;
- importación completa.

Cada importación reemplaza todos los datos existentes. No existen cargas incrementales ni fusiones.

La copia se maneja como un único archivo ZIP directamente exportable e importable por la app, sin manipulación manual.

El ZIP incluye:

- JSON versionado con los datos;
- imágenes necesarias.

La importación debe validar compatibilidad e integridad antes de sobrescribir.

## 22. Limpieza histórica

Existe eliminación masiva de datos transaccionales anteriores a una fecha de corte exacta.

No elimina datos maestros.

Antes del borrado se muestra:

- fecha de corte;
- número de sesiones afectadas;
- número de ejercicios/series asociados;
- espacio estimado que podría liberarse cuando sea calculable de forma fiable.

La confirmación debe ser fuerte e indicar que el borrado es irreversible.

Desde esa misma confirmación se recomienda y se ofrece generar copia de seguridad.

Después del borrado:

- se compacta automáticamente la base de datos para intentar recuperar espacio físico;
- se muestra resumen con registros eliminados y espacio realmente recuperado.

## 23. Pantalla principal

La pantalla principal es un panel simple, no un dashboard de métricas.

Bloque principal: `Hoy`.

Si existen sesiones hoy, se muestran todas con prioridad:

1. `En curso` → acción principal `Continuar`;
2. `Planificadas` → `Empezar`;
3. `Realizadas` → `Ver`.

Si hay una sola sesión, puede mostrarse como tarjeta principal única.

`Nueva sesión` permanece siempre accesible.

Cuando existe una sesión `En curso`, `Continuar` tiene mayor protagonismo visual y `Nueva sesión` queda como acción secundaria.

Accesos rápidos:

- Sesiones
- Rutinas
- Ejercicios
- Histórico

## 24. Apariencia y accesibilidad

Modos de tema:

- Sistema
- Claro
- Oscuro

Valor inicial: Sistema.

La aplicación respeta el tamaño de texto configurado en Android.

Diseño objetivo v1: teléfono en orientación vertical.

## 25. Imágenes

Cada ejercicio puede mostrar entre 1 y 3 imágenes estáticas.

Las imágenes del catálogo inicial deben ser legalmente utilizables con el proyecto y no depender de recursos remotos para funcionar.

Los ejercicios personalizados permiten añadir imágenes desde galería.

Las imágenes forman parte de la copia completa de seguridad.

Animaciones/GIF quedan fuera de v1.

## 26. Fuera de alcance v1

Entre otros:

- sincronización multi-dispositivo;
- autenticación, PIN o biometría;
- Google Play;
- notificaciones/recordatorios;
- orientación horizontal/tablet;
- tolerancia a errores tipográficos en búsqueda;
- filtro por equipo;
- patrón de movimiento;
- distancia como unidad activa en interfaz;
- animaciones/GIF;
- métricas derivadas avanzadas;
- comparación automática en el informe con sesiones anteriores;
- archivado de sesiones.

## 27. Decisiones todavía pendientes

Este documento seguirá creciendo durante el cierre de producto. No debe inferirse ninguna decisión que no esté recogida aquí o en una issue/ADR aprobada.
