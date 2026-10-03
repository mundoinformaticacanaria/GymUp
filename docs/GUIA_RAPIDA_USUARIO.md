# Guía rápida de GymUp

GymUp sirve para preparar rutinas, planificar sesiones y registrar lo que haces realmente en el gimnasio. Funciona sin conexión a Internet.

## La ruta más corta para empezar

1. En Inicio, abre **Rutinas**.
2. Pulsa **Nueva rutina**.
3. Completa los tres pasos: **Datos**, **Ejercicios** y **Resumen**.
4. Pulsa **Guardar**.
5. Vuelve a Inicio y pulsa **Nueva sesión**.
6. Elige **Desde rutina**, selecciona la rutina, revisa fecha y tipo, y pulsa **Crear sesión**.
7. En la sesión, pulsa **Iniciar sesión** y abre el ejercicio que vas a realizar.

## Crear o editar una rutina

El asistente divide el trabajo en tres pantallas:

1. **Datos**: escribe un nombre. La descripción y el tipo sugerido son opcionales.
2. **Ejercicios**: busca por nombre o filtra por grupo muscular y pulsa **Añadir**.
3. **Resumen**: revisa la selección, cambia el orden con **Subir** o **Bajar**, o usa **Quitar**.

Pulsa **Siguiente** para avanzar, **Anterior** para volver y **Guardar** al terminar. Editar o eliminar una rutina no modifica las sesiones que ya se hayan creado desde ella.

## Crear una sesión

Pulsa **Nueva sesión** desde Inicio o Sesiones y elige un origen:

- **Sesión vacía**: empieza sin ejercicios.
- **Desde rutina**: copia los ejercicios de una rutina.
- **Duplicar**: reutiliza la planificación de otra sesión.

Después indica la fecha con formato `AAAA-MM-DD`, el tipo y, si quieres, un nombre y una nota. Pulsa **Crear sesión**. Entrarás en la planificación, donde todavía puedes añadir ejercicios o revisar sus objetivos antes de iniciar.

## Registrar un ejercicio y sus series

Cada serie separa lo planificado de lo realizado:

- **Objetivo**: la carga y la medición previstas. Pulsa **Guardar objetivo** después de cambiarlas.
- **Real**: la carga, la medición y el RIR que hiciste realmente. Pulsa **Guardar real** después de introducirlos.

Una serie pasa de **Pendiente** a **Realizada** cuando guardas al menos un dato real: carga, medición o RIR.

### Qué hace Cumplido

**Cumplido** es un atajo: copia la carga y la medición de **Objetivo** a **Real**.

- Antes debes tener guardado al menos un valor objetivo.
- No completa el RIR.
- Si necesitas RIR, selecciónalo y pulsa **Guardar real**.
- Los datos copiados siguen siendo editables.

Tras **Guardar real** o **Cumplido**, GymUp muestra el resultado junto a la serie. Si dejas vacíos todos los datos reales y guardas, la serie vuelve a **Pendiente**.

### RIR en una frase

RIR significa «repeticiones en reserva»: cuántas repeticiones más crees que podrías haber hecho. Elige `0`, `1`, `2` o `—` si no quieres informar un valor opcional. Si el ejercicio exige RIR, GymUp no permitirá finalizar mientras falte en una serie realizada.

## Finalizar correctamente

- **Finalizar ejercicio** cierra el ejercicio en el que estás trabajando.
- **Volver a la sesión** regresa a la lista de ejercicios.
- **Finalizar sesión** cierra la sesión completa.

Si cerraste una sesión por error o necesitas corregirla, ábrela y pulsa **Reabrir sesión**. Tras confirmar, volverá a **En curso** conservando todos los datos. Podrás editarla o continuarla y después pulsar otra vez **Finalizar sesión**.

Los estados del ejercicio se calculan automáticamente:

- **No realizado**: ninguna serie realizada.
- **Parcial**: algunas series realizadas y otras pendientes.
- **Completado**: todas las series realizadas.

## Histórico

En **Histórico** puedes filtrar sesiones por estado, resultado, tipo y fechas. Abre una sesión con **Ver / editar**. Las sesiones realizadas también permiten guardar o compartir su informe JSON.

## Ajustes y seguridad de los datos

En **Ajustes** puedes:

- elegir tema **Sistema**, **Claro** u **Oscuro**;
- gestionar tipos de sesión, grupos musculares y equipos;
- crear o importar una copia de seguridad completa;
- calcular y eliminar datos históricos anteriores a una fecha.

Importar una copia de seguridad sustituye todos los datos actuales. La limpieza histórica es irreversible: crea antes un backup si necesitas conservar esa información.

## Dudas rápidas

### Pulso Cumplido y no puedo usarlo

Guarda primero una carga o medición objetivo. **Cumplido** solo puede copiar objetivos ya guardados.

### Cumplido no ha guardado el RIR

Es el comportamiento previsto. Selecciona el RIR y pulsa **Guardar real**.

### He escrito datos reales, pero la serie sigue pendiente

Pulsa **Guardar real**. Comprueba el mensaje que aparece junto a la serie.

### No puedo finalizar la sesión

Revisa si algún ejercicio exige RIR y hay una serie realizada sin ese dato.

### Desde rutina o Duplicar están desactivados

Primero debe existir al menos una rutina o una sesión anterior, respectivamente.

### No encuentro una acción o más ejercicios

Las listas largas pueden desplazarse verticalmente. Los botones principales de los asistentes permanecen en la zona inferior de la pantalla.
