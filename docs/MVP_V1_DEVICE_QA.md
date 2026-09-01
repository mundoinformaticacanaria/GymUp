# GymUp MVP v1 — validación final en dispositivo

Este checklist cubre las comprobaciones que no pueden certificarse únicamente mediante tests unitarios, lint y compilación en GitHub Actions.

## Dispositivo objetivo

- Android 15 o Android 16.
- Teléfono en orientación vertical.
- Instalar el artefacto `gymup-v1-release` generado por el CI del SHA final.

## Home y navegación

1. Los accesos rápidos deben tener apariencia claramente habilitada; no deben parecer controles desactivados.
2. Con una sesión En curso hoy, Home prioriza **Continuar**.
3. Con una Planificada, muestra **Empezar**.
4. Con una Realizada, muestra **Ver**.
5. `Nueva sesión` permanece accesible.
6. Sesiones, Rutinas, Ejercicios, Histórico y Ajustes son alcanzables y permiten volver sin perder el flujo.

## Creación y planificación de sesión

1. Abrir `Nueva sesión` y comprobar que el flujo no depende de descubrir contenido fuera de pantalla.
2. Si no existen rutinas, `Desde rutina` debe explicarlo claramente y no dejar una selección vacía confusa.
3. Si no existen sesiones duplicables, `Duplicar sesión` debe explicarlo claramente.
4. Tipo, fecha y demás metadatos deben mostrarse de forma compacta, con edición explícita cuando se quiera cambiar.
5. Crear una sesión vacía.
6. Añadir ejercicios usando búsqueda incremental por nombre ES/EN.
7. Crear un ejercicio con defaults de series/carga/medición y comprobar que esos defaults se precargan al incorporarlo a una sesión sin histórico válido previo.

## Ejecución de sesión

1. Desde una sesión Planificada, iniciar sesión mediante una acción clara `Iniciar sesión`.
2. El estado operativo debe evolucionar automáticamente con el trabajo real; la manipulación manual del estado no debe ser el flujo ordinario.
3. En modo ejecución, comprobar que se presenta primero la lista de ejercicios planificados.
4. Abrir un ejercicio y comprobar que se muestran solo sus series, instrucciones e imágenes.
5. Deben existir acciones claras `Finalizar ejercicio` y `Volver a la sesión` para escoger otro ejercicio.
6. Registrar objetivo y real, seleccionar RIR, usar `Cumplido` y descansos.
7. Finalizar la sesión y comprobar estado/resultados derivados.
8. Abrir Histórico y volver a abrir la sesión realizada.
9. Abrir histórico del ejercicio y cambiar entre Carga/Medición.

## Catálogos y ejercicios

1. Crear un tipo de sesión personalizado y usarlo en una sesión.
2. Comprobar que `Otro` de Tipo de sesión no puede renombrarse ni desactivarse.
3. Crear un grupo muscular y un equipo personalizados.
4. Crear un ejercicio personalizado con nombres ES/EN, defaults y RIR.
5. Al guardar, debe mostrarse feedback visible y existir acción clara `Volver`/`Cancelar` sin depender del gesto/botón del teléfono.
6. Reabrir el ejercicio y comprobar que los defaults guardados persisten.
7. Añadir una imagen desde galería y comprobar que aparece en la ficha.
8. Añadir ese ejercicio a una sesión y comprobar que instrucciones/imágenes pueden consultarse sin abandonar el registro.
9. Desactivar/borrar un ejercicio y comprobar la confirmación adecuada según tenga histórico o referencias en rutinas.

## Apariencia y accesibilidad

Repetir al menos el smoke test básico con:

- Tema Sistema.
- Tema Claro.
- Tema Oscuro.
- Navegación Android mediante tres botones.
- Navegación Android mediante gestos, cuando esté disponible.
- Tamaño de fuente Android normal.
- Tamaño de fuente Android aumentado de forma significativa.

Comprobar especialmente:

- textos no superpuestos;
- acciones inferiores completamente visibles y pulsables por encima de la navegación del sistema;
- ausencia de pulsaciones accidentales sobre Volver, Inicio o Aplicaciones recientes al usar los CTA de GymUp;
- botones principales alcanzables mediante scroll;
- formularios de sesión y Ajustes utilizables;
- no existen controles esenciales fuera de pantalla sin posibilidad de scroll;
- tarjetas y acciones siguen siendo comprensibles con texto grande.

## Orientación

- Confirmar que la aplicación permanece en vertical durante navegación y entrenamiento.

## Backup e informe

1. Finalizar una sesión y guardar su informe JSON.
2. Compartir el informe mediante el menú estándar de Android.
3. Exportar backup ZIP completo.
4. Crear/modificar datos después del backup.
5. Importar el backup y confirmar el `replace-all`.
6. Verificar que la imagen personalizada del ejercicio también se restaura.

## Limpieza histórica

1. Solicitar vista previa con una fecha de corte que afecte datos de prueba.
2. Verificar recuentos y advertencia de irreversibilidad.
3. Cancelar una vez para comprobar que no se borra nada.
4. Repetir y confirmar el borrado.
5. Comprobar que maestros y ejercicios se conservan.

## Criterio de cierre

La Issue #8 puede cerrarse cuando:

- el CI del SHA final está completamente verde;
- el APK `gymup-v1-release` se genera correctamente;
- este checklist no descubre bloqueos P0/P1 en el dispositivo objetivo.

Cualquier defecto descubierto se corrige antes de cerrar #8. Si la validación física requiere intervención del propietario, #8 permanece abierta y etiquetada `PROPIETARIO`; si el propietario aporta incidencias reproducibles, vuelve a `TECH LEAD` hasta generar un nuevo candidato.
