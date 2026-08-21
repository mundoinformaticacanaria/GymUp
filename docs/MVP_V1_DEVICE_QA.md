# GymUp MVP v1 — validación final en dispositivo

Este checklist cubre las comprobaciones que no pueden certificarse únicamente mediante tests unitarios, lint y compilación en GitHub Actions.

## Dispositivo objetivo

- Android 15 o Android 16.
- Teléfono en orientación vertical.
- Instalar el artefacto `gymup-v1-release` generado por el CI del SHA final.

## Smoke test

1. La aplicación instala y abre sin crash.
2. Home muestra el bloque **Hoy** y permite crear una sesión.
3. Crear una sesión vacía, añadir un ejercicio y una serie.
4. Registrar objetivo y real, seleccionar RIR y usar `Cumplido` en otra serie.
5. Finalizar la sesión y comprobar estado/resultados.
6. Abrir Histórico y volver a abrir la sesión realizada.
7. Abrir histórico del ejercicio y cambiar entre Carga/Medición.

## Catálogos y ejercicios

1. Crear un tipo de sesión personalizado y usarlo en una sesión.
2. Comprobar que `Otro` de Tipo de sesión no puede renombrarse ni desactivarse.
3. Crear un grupo muscular y un equipo personalizados.
4. Crear un ejercicio personalizado con nombres ES/EN, defaults y RIR.
5. Añadir una imagen desde galería y comprobar que aparece en la ficha.
6. Añadir ese ejercicio a una sesión y comprobar que instrucciones/imágenes pueden consultarse sin abandonar el registro.
7. Desactivar/borrar un ejercicio y comprobar la confirmación adecuada según tenga histórico o referencias en rutinas.

## Home y navegación

1. Con una sesión En curso hoy, Home prioriza **Continuar**.
2. Con una Planificada, muestra **Empezar**.
3. Con una Realizada, muestra **Ver**.
4. `Nueva sesión` permanece accesible.
5. Sesiones, Rutinas, Ejercicios, Histórico y Ajustes son alcanzables y permiten volver sin perder el flujo.

## Apariencia y accesibilidad

Repetir al menos el smoke test básico con:

- Tema Sistema.
- Tema Claro.
- Tema Oscuro.
- Tamaño de fuente Android normal.
- Tamaño de fuente Android aumentado de forma significativa.

Comprobar especialmente:

- textos no superpuestos;
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

Cualquier defecto descubierto se registra como Issue antes de cerrar #8. Si la validación física requiere intervención del propietario, #8 debe permanecer abierta y etiquetarse `PROPIETARIO` hasta recibir el resultado.
