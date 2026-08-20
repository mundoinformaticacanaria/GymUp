# GymUp — Contrato funcional v1

Estado: **vivo / en definición**

Este documento recoge las decisiones de producto vigentes para GymUp v1. Si una conversación, Issue, ADR o implementación contradice este documento, el trabajo afectado debe detenerse hasta resolver explícitamente la discrepancia.

## 1. Alcance general

GymUp v1 es una aplicación Android monousuario y offline para:

- planificar sesiones de entrenamiento;
- ejecutar y registrar sesiones;
- mantener catálogos maestros relacionados con el entrenamiento;
- reutilizar rutinas;
- consultar histórico;
- visualizar evolución por ejercicio;
- generar informes JSON por sesión realizada;
- exportar/importar una copia completa de seguridad.

Distribución inicial: APK instalada manualmente.

Compatibilidad mínima: Android 15. Dispositivo principal objetivo: Android 16.

Diseño objetivo: teléfono en orientación vertical.

Interfaz: español. Los ejercicios muestran nombre en español e inglés.

No existe autenticación, perfiles, PIN ni biometría en v1.

## 2. Principios de producto

- La aplicación debe reducir fricción y clics durante el entrenamiento.
- Los defaults deben ser fuertes, pero nunca rígidos.
- No se deben inferir datos reales de ejecución que el usuario no haya confirmado.
- Los datos maestros pueden evolucionar; el histórico no se reescribe automáticamente por esos cambios.
- Las sesiones históricas conservan snapshots suficientes para interpretar exactamente cómo estaban configuradas cuando se registraron.
- Las métricas y gráficas utilizan exclusivamente trabajo realmente ejecutado.
- Toda funcionalidad principal debe funcionar sin conexión a Internet.

## 3. Sesiones

### 3.1 Identidad, fecha y orden

Una sesión tiene, como mínimo:

- identificador interno;
- fecha;
- `orden_en_dia`;
- tipo de sesión;
- nombre visible;
- nota general opcional;
- estado operativo;
- resultado de ejecución.

No existen `hora_inicio` ni `hora_fin` en v1.

Se permiten múltiples sesiones en una misma fecha.

`orden_en_dia` es un orden de negocio editable entre sesiones del mismo día. No depende del ID técnico ni del nombre visible.

Nombre automático:

`<Día de semana> <dd/mm/aaaa> S<n>`

Ejemplo:

`Martes 18/08/2026 S1`

El nombre es opcional para el usuario, autogenerado si queda vacío y siempre editable.

Si el nombre sigue siendo autogenerado y cambia `orden_en_dia`, el nombre se actualiza. Si fue personalizado, se conserva.

### 3.2 Estado operativo

Valores:

- `Planificada`
- `En curso`
- `Realizada`

Reglas:

- una sesión `Planificada` pasa automáticamente a `En curso` cuando se registra el primer dato real;
- solo pasa a `Realizada` cuando el usuario pulsa `Finalizar sesión`;
- puede finalizarse aunque existan ejercicios o series sin completar;
- puede finalizarse sin ningún dato real, quedando `Realizada + No iniciada`;
- editar una sesión `Realizada` no la devuelve automáticamente a `En curso`;
- cualquier cambio desde `Realizada` a otro estado es manual;
- no existe estado `Cancelada` en v1.

### 3.3 Resultado de ejecución

Valores:

- `No iniciada`
- `Parcial`
- `Completada`

El resultado se recalcula automáticamente cuando cambian datos reales, incluso si la sesión ya está `Realizada`.

Estado operativo y resultado son conceptos independientes.

Ejemplos válidos:

- `Planificada + No iniciada`
- `En curso + Parcial`
- `Realizada + No iniciada`
- `Realizada + Parcial`
- `Realizada + Completada`

### 3.4 Creación

Al pulsar `Nueva sesión`, el usuario elige primero:

1. `Desde rutina`
2. `Duplicar sesión`
3. `Sesión vacía`

Después se muestra una pantalla común breve:

- Fecha: obligatoria, por defecto hoy, editable a pasado o futuro.
- Tipo de sesión: obligatorio.
- Nombre: opcional, autogenerado si queda vacío.
- Nota general: opcional.

`Duplicar sesión` copia únicamente lista de ejercicios + orden. No copia series, objetivos ni datos reales. El tipo de sesión original se precarga como sugerencia y sigue siendo editable.

`Desde rutina` copia únicamente lista de ejercicios + orden. Si la rutina tiene tipo sugerido, se precarga y sigue siendo editable.

### 3.5 Edición, reordenación y borrado

Una sesión puede editarse siempre, antes, durante o después de finalizarla.

Los ejercicios pueden reordenarse antes, durante y después.

Se puede eliminar cualquier sesión —`Planificada`, `En curso` o `Realizada`— con confirmación previa.

Una sesión que no llegó a ejecutarse puede conservarse como `Realizada + No iniciada` o eliminarse.

No existe archivado en v1.

## 4. Ejercicios dentro de una sesión

### 4.1 Instancia y snapshot

Al añadir un ejercicio maestro a una sesión se crea una instancia propia de esa sesión.

Un mismo ejercicio maestro no puede aparecer más de una vez en la misma sesión. Si ya existe, la app impide añadirlo de nuevo y facilita acceder al existente.

La instancia guarda snapshot de los datos necesarios para interpretar el histórico, incluyendo al menos:

- nombre español;
- nombre inglés;
- grupo muscular mostrado;
- equipo mostrado, si existe;
- modalidad de carga;
- unidad de medición;
- demás defaults necesarios para interpretar la ejecución.

Editar la instancia de una sesión no modifica el maestro.

Editar el maestro no modifica instancias ya creadas ni el histórico.

### 4.2 Series: objetivo y real

Cada serie separa explícitamente:

- peso/carga objetivo;
- peso/carga real;
- medición objetivo;
- medición real;
- RIR;
- descanso aplicable.

Los valores precargados pertenecen a `Objetivo`. `Real` comienza vacío/no confirmado.

Existe una acción rápida `Cumplido` que copia carga objetivo y medición objetivo a sus campos reales. Los valores copiados siguen siendo editables.

`Cumplido` nunca completa RIR.

Se pueden añadir y eliminar series libremente dentro de la sesión.

### 4.3 Estado de serie

- `Pendiente`: no contiene ningún dato real confirmado de la sesión actual.
- `Realizada`: contiene al menos un dato real de ejecución aplicable.

Los valores de objetivo no convierten una serie en realizada hasta que el usuario los copie/confirme como reales o introduzca datos reales.

### 4.4 Estado de ejercicio

Valores:

- `No realizado`
- `Parcial`
- `Completado`

Cálculo automático:

- 0 series realizadas → `No realizado`;
- al menos una realizada y alguna pendiente → `Parcial`;
- todas las series realizadas → `Completado`.

El ejercicio puede finalizarse manualmente en cualquier momento. Al finalizar la sesión se finalizan los ejercicios aún abiertos.

El estado calculado puede modificarse manualmente después. El efecto exacto de ese override sobre resultado de sesión y futuras precargas permanece pendiente de cierre funcional en la Issue #1; no debe inferirse.

Si un ejercicio no queda completado, puede registrarse opcionalmente un motivo a nivel de ejercicio:

- máquina ocupada;
- falta de tiempo;
- molestia;
- fatiga;
- decisión de la sesión;
- otro.

No se requiere motivo por serie.

## 5. Precarga de objetivos

Para una sesión con fecha y `orden_en_dia` determinados, la fuente de referencia es temporalmente coherente.

Prioridad:

1. última ejecución `Completado` del mismo ejercicio estrictamente anterior a la posición temporal de la sesión;
2. si no existe, objetivos iniciales opcionales de la ficha maestra;
3. si tampoco existen, campos vacíos.

`Parcial` y `No realizado` no sirven como fuente de precarga.

Una sesión posterior del mismo día puede usar una ejecución completada de una sesión anterior según `orden_en_dia`. Una sesión nunca utiliza como referencia una ejecución posterior a su posición temporal.

La última ejecución completada aporta también el número inicial de series.

Si no existe histórico completado, el maestro puede aportar opcionalmente:

- número de series;
- carga inicial;
- medición inicial.

El objetivo inicial del maestro es común y se replica en las series iniciales.

Si la ejecución de referencia tiene N series y el usuario añade una serie N+1 o posteriores, las nuevas series copian los objetivos de la **última serie disponible de esa misma ejecución de referencia**. No se buscan antiguas series adicionales en otras ejecuciones históricas.

Si un objetivo de la última serie de referencia está vacío, se conserva vacío; no se inventa.

## 6. Carga

Modalidades v1:

- `kg total`
- `kg/mano`
- `kg/lado`
- `peso corporal`
- `peso corporal + X kg`
- `peso corporal - X kg asistencia`
- `sin peso`

Se guarda el valor exactamente según su modalidad. No se calcula una carga total efectiva derivada.

Para `peso corporal + X kg` y `peso corporal - X kg asistencia`, el valor numérico es exclusivamente `X`; nunca se suma ni se infiere el peso corporal del usuario.

Las modalidades nunca se convierten automáticamente entre sí.

Peso en kg: hasta 2 decimales.

La interfaz española usa coma decimal.

## 7. Medición

Unidades v1:

- `repeticiones`
- `repeticiones/lado`
- `segundos`
- `segundos/lado`

Repeticiones y segundos son enteros.

`Distancia` queda prevista para evolución futura del modelo, pero fuera de la interfaz v1.

Las unidades no se convierten automáticamente entre sí.

## 8. RIR

RIR es nullable en persistencia.

Cada ejercicio maestro tiene `RIR obligatorio`, activado por defecto y editable.

Valores permitidos en v1:

- `0`
- `1`
- `2`

La interfaz utiliza tres botones rápidos `0 / 1 / 2`.

Si `RIR obligatorio` está desactivado, no se solicita ni genera aviso obligatorio.

Si está activado y una serie tiene datos reales, esa serie no puede validarse/finalizarse con RIR vacío.

Al finalizar una sesión, si existen series realizadas con RIR obligatorio pendiente, la aplicación solicita los RIR faltantes antes de permitir el cierre.

Las series pendientes no requieren RIR.

Cambiar posteriormente el maestro de RIR opcional a obligatorio no invalida históricos antiguos con RIR nulo.

## 9. Descanso y notas

### 9.1 Descanso

El descanso se introduce manualmente.

Puede definirse:

- por ejercicio, como default para sus series;
- por serie, como override individual.

Se puede introducir en segundos o minutos. Segundos es la unidad por defecto.

Internamente puede normalizarse a segundos.

### 9.2 Notas

- Nota general de sesión: opcional.
- Nota por ejercicio: opcional, para molestias, fatiga, incidencias u observaciones.
- No existe requisito de nota por serie en v1.

## 10. Catálogo maestro de ejercicios

La v1 arranca con un catálogo curado de aproximadamente 60–100 ejercicios.

FUNCIONAL es responsable de preparar y validar el contenido funcional inicial siguiendo `docs/data/EXERCISE_CATALOG_TEMPLATE.md`. El Tech Lead validará estructura, enums, duplicados, catálogos referenciados y licencias antes de aceptar el seed.

No se inventarán objetivos iniciales solo para completar campos.

Campos contemplados:

- `nombre_es`;
- `nombre_en`;
- grupo muscular principal;
- equipo opcional;
- modalidad de carga por defecto;
- unidad de medición por defecto;
- `RIR obligatorio`;
- número inicial de series opcional;
- carga inicial opcional;
- medición inicial opcional;
- descripción/instrucciones breves opcionales;
- 0–3 imágenes estáticas;
- favorito sí/no;
- activo/inactivo cuando corresponda.

Español e inglés son atributos del mismo ejercicio; no se duplican ejercicios por idioma.

El catálogo no permite nombres repetidos en `nombre_es` ni en `nombre_en`.

La unicidad normaliza mayúsculas/minúsculas y tildes/diacríticos. Ejemplo: `Press`, `PRESS` y `PrÉss` se consideran el mismo nombre.

Formato visual:

`Nombre español · English name`

con el mismo peso visual.

Los favoritos los marca/desmarca explícitamente el usuario; no se infieren por frecuencia.

Los ejercicios personalizados pueden crearse y pueden añadir imágenes desde galería. No se usa cámara en v1.

Durante una sesión se deben poder abrir imágenes e instrucciones sin abandonar el registro.

### 10.1 Baja y borrado

Si un ejercicio tiene histórico, eliminarlo desde mantenimiento implica baja lógica:

- deja de estar disponible para nuevas incorporaciones normales;
- deja de aparecer en búsquedas normales;
- conserva el histórico.

Solo ejercicios sin histórico pueden optar a borrado definitivo desde gestión normal.

El comportamiento cuando un ejercicio desactivado o borrable está referenciado por una rutina o por una duplicación histórica está pendiente de cierre funcional en la Issue #1.

## 11. Búsqueda de ejercicios

Búsqueda incremental/reactiva por `nombre_es` y `nombre_en`; no existe botón de búsqueda.

Normalización:

- ignora mayúsculas/minúsculas;
- ignora tildes/diacríticos.

No existe tolerancia a errores tipográficos en v1.

Orden principal de coincidencia:

1. nombres que empiezan por el texto buscado;
2. nombres que contienen el texto buscado.

Dentro de **cada bloque** se aplica esta prioridad secundaria:

1. Favoritos.
2. Ejercicios usados anteriormente, ordenados por su última ejecución real de más reciente a más antigua.
3. Ejercicios presentes en alguna rutina y no priorizados por los criterios anteriores.
4. Resto.
5. Desempate estable: `nombre_es` normalizado alfabéticamente y después `nombre_en`.

Si un ejercicio cumple varios criterios, pertenece únicamente al nivel de mayor prioridad aplicable.

No existe umbral temporal adicional para considerar uso reciente.

Filtro v1: grupo muscular.

Filtro por equipo: fuera de v1.

Filtro por patrón de movimiento: fuera de v1.

## 12. Catálogos auxiliares

### 12.1 Grupo muscular

Cada ejercicio tiene un único grupo muscular principal en v1.

Catálogo editable: crear, renombrar y desactivar.

Lista inicialmente propuesta, pendiente de confirmación funcional en Issue #1:

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

Los cambios del maestro no reescriben snapshots históricos.

### 12.2 Equipo

Equipo es opcional en el ejercicio.

Catálogo editable: crear, renombrar y desactivar.

Lista inicialmente propuesta, pendiente de confirmación funcional en Issue #1:

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

Los cambios del maestro no reescriben snapshots históricos.

### 12.3 Tipo de sesión

Tipo de sesión es obligatorio al crear sesión y pertenece a un catálogo editable.

Lista inicial aprobada:

- `Fuerza`
- `Hipertrofia`
- `Cardio`
- `Movilidad`
- `Deporte`
- `Recuperación`
- `Otro`

Nombres específicos como `Pádel`, `Full body` o `Pre-pádel` pueden añadirse por el usuario; no forman parte del seed inicial.

La selección debe ser rápida y puede recordar el último tipo usado cuando tenga sentido.

Si un tipo usado históricamente deja de estar activo, las sesiones antiguas conservan su snapshot.

La protección concreta de `Otro` y las reglas de unicidad de los catálogos editables permanecen pendientes en Issue #1.

### 12.4 Patrón de movimiento

Fuera de v1. El modelo debe permitir incorporarlo en una evolución posterior sin romper datos existentes.

## 13. Rutinas

Una rutina es una plantilla maestra sin vínculo persistente con las sesiones creadas a partir de ella.

Campos v1:

- nombre;
- tipo de sesión sugerido opcional;
- descripción/nota opcional;
- lista ordenada de ejercicios.

Una rutina no puede contener dos veces el mismo ejercicio maestro.

No guarda objetivos de peso, series o medición.

Al crear una sesión desde rutina:

- se copian ejercicios + orden;
- cada ejercicio calcula objetivos desde histórico/defaults;
- el tipo sugerido se precarga si existe y sigue siendo editable;
- la descripción de la rutina no se copia a la nota de sesión.

Editar una rutina afecta solo a sesiones futuras.

Las rutinas pueden editarse o eliminarse. No existe activar/desactivar rutina en v1.

Eliminar una rutina nunca modifica sesiones ya creadas.

## 14. Histórico de sesiones

Filtros combinables:

- estado operativo;
- resultado de ejecución;
- tipo de sesión;
- rango exacto desde/hasta.

Los filtros pueden mantenerse mientras el usuario permanece en la pantalla, pero no persisten entre reaperturas del histórico en v1.

Las sesiones históricas pueden editarse.

## 15. Histórico por ejercicio y gráficas

La gráfica utiliza las **últimas 10 ejecuciones válidas de ese ejercicio**, no las últimas 10 sesiones generales.

Una ejecución es válida si tiene al menos una serie realizada:

- `Parcial` entra;
- `Completado` entra;
- `No realizado` queda fuera.

Solo se grafican datos reales ejecutados. Las series planificadas pendientes no generan puntos.

Las ejecuciones `Parcial` deben ser visualmente identificables respecto de las `Completado`.

No existe filtro `Solo completadas` en v1.

Selector:

- `Carga`
- `Medición`

Cada serie se representa como línea independiente. Si una serie no existe en una ejecución, no genera punto.

### 15.1 Gráfica Carga

`Carga` se oculta cuando el ejercicio no tiene valor numérico de carga aplicable.

Para lastre/asistencia se representa solo `X`.

Cada modalidad se compara exclusivamente consigo misma. Nunca se convierten modalidades automáticamente.

Si cambia la modalidad entre ejecuciones, se corta la línea o se crea una serie visual distinta por modalidad.

Ejemplo: `10 kg/mano → 12,5 kg/mano → 25 kg total` no se representa como una progresión continua.

### 15.2 Gráfica Medición

`repeticiones`, `repeticiones/lado`, `segundos` y `segundos/lado` son dominios diferentes.

Cada unidad se compara exclusivamente consigo misma. Nunca se convierten unidades automáticamente.

Si cambia la unidad entre ejecuciones, se corta la línea o se crea una serie visual distinta por unidad.

Ejemplo: `30 segundos → 40 segundos → 12 repeticiones` no se representa como una progresión continua.

No se incluyen métricas derivadas como volumen total o 1RM estimado en v1.

## 16. Informes de sesión

Solo una sesión en estado `Realizada` puede generar informe.

Cada sesión genera un JSON independiente, estructurado y versionado.

Contenido mínimo:

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
- carga objetivo y real;
- medición objetivo y real;
- RIR cuando exista;
- descansos;
- notas/incidencias;
- motivo opcional de no finalización.

Planificado y ejecutado deben quedar inequívocamente separados.

El archivo puede:

- guardarse mediante el selector estándar de Android;
- compartirse mediante el menú estándar de Android.

Nombre de archivo: `fecha + nombre visible de sesión`, saneando únicamente caracteres técnicamente inválidos.

## 17. Copia de seguridad e importación

Persistencia local; no existe sincronización multidispositivo en v1.

La aplicación permite:

- exportación completa;
- importación completa.

La copia completa es un único archivo ZIP directamente exportable e importable por GymUp, sin descomprimir ni manipular manualmente.

Incluye:

- JSON versionado con datos;
- imágenes necesarias.

Cada importación reemplaza **todos** los datos existentes. No existen merge ni importaciones incrementales.

Antes de sobrescribir, la aplicación valida compatibilidad e integridad.

La necesidad o no de protección adicional del ZIP mediante contraseña/cifrado queda pendiente de cierre funcional en Issue #1.

## 18. Limpieza histórica

Existe una función para eliminar datos transaccionales anteriores a una fecha de corte exacta.

Nunca elimina datos maestros.

Antes del borrado muestra:

- fecha de corte;
- número de sesiones afectadas;
- número de ejercicios/series/registros asociados;
- espacio estimado a liberar cuando pueda calcularse razonablemente.

La confirmación debe ser fuerte e indicar irreversibilidad.

Desde la misma confirmación se recomienda y ofrece generar backup.

Tras el borrado:

- se compacta la base de datos para intentar recuperar espacio físico;
- se muestran registros eliminados y espacio realmente recuperado.

## 19. Pantalla principal

Panel simple, no dashboard de métricas.

Bloque principal: `Hoy`.

Si existen varias sesiones hoy, se priorizan:

1. `En curso` → `Continuar`
2. `Planificada` → `Empezar`
3. `Realizada` → `Ver`

Si existe una sola sesión, puede mostrarse como tarjeta principal única.

`Nueva sesión` permanece siempre accesible.

Si hay una sesión `En curso`, `Continuar` tiene más protagonismo visual y `Nueva sesión` queda como acción secundaria.

Accesos rápidos:

- Sesiones
- Rutinas
- Ejercicios
- Histórico

## 20. Apariencia y accesibilidad

Tema:

- `Sistema`
- `Claro`
- `Oscuro`

Valor inicial: `Sistema`.

La aplicación respeta el tamaño de texto configurado en Android y debe mantener tablas/formularios utilizables con escalado de fuente.

## 21. Imágenes

Un ejercicio puede tener **0–3 imágenes estáticas**.

Las imágenes son deseables pero opcionales y su ausencia no bloquea un ejercicio ni la v1.

Cuando se incluyan:

- deben ser recursos propios o con licencia compatible;
- deben estar disponibles offline en ejecución;
- se priorizan cuando aportan valor real para identificar o ejecutar el movimiento;
- forman parte del backup completo cuando corresponda a datos del usuario.

Los ejercicios personalizados pueden seleccionar imágenes desde galería.

No se usa cámara en v1.

Animaciones y GIF quedan fuera de v1.

## 22. Fuera de alcance v1

Entre otros:

- sincronización multi-dispositivo;
- autenticación, PIN o biometría;
- publicación en Google Play;
- notificaciones/recordatorios;
- tablet/horizontal;
- tolerancia a errores tipográficos en búsqueda;
- filtro de búsqueda por equipo;
- patrón de movimiento;
- distancia activa en la interfaz;
- animaciones/GIF;
- métricas derivadas avanzadas;
- comparación automática del informe con sesiones anteriores;
- archivado de sesiones;
- filtro histórico `Solo completadas`.

## 23. Decisiones pendientes y circuito de cierre

Las dudas funcionales pendientes del MVP v1 se centralizan en la Issue `#1 — [FUNCIONAL] Cierre de dudas pendientes — MVP v1`.

Circuito:

1. label `FUNCIONAL`: trabaja el funcional;
2. al terminar cambia a `TECH LEAD`;
3. el Tech Lead revisa respuestas, coherencia, contrato y ADR;
4. si quedan dudas/contradicciones, el Tech Lead comenta únicamente lo pendiente y devuelve la label a `FUNCIONAL`;
5. el ciclo se repite hasta cerrar funcionalmente el MVP v1.

Cuando el usuario indique `Revisa GitHub` o equivalente, el Tech Lead debe localizar todas las Issues abiertas con label `TECH LEAD` y procesarlas sin pedir que se copie su contenido en el chat.

No debe inferirse una decisión funcional pendiente.

El Tech Lead es responsable de mantener GitHub y este contrato actualizados durante todo el circuito.
