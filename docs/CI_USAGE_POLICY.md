# GymUp — Política de uso de GitHub Actions

Estado: **vigente**  
Fecha de entrada en vigor: 2026-08-30  
Issue origen: #26

## Objetivo

Minimizar las ejecuciones y el almacenamiento de GitHub Actions sin perder la verificación automática necesaria para integrar cambios con seguridad.

## Verificación habitual

El workflow `Android CI` se ejecuta:

- una vez por pull request con cambios de código;
- en cada actualización de `main` con cambios de código.

No se ejecuta cuando los únicos cambios están en `docs/**`, `README.md` o `Log/**`. Una actualización nueva cancela la ejecución anterior del mismo pull request.

La verificación usa un runner estándar `ubuntu-latest` y ejecuta en una sola invocación:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Esta verificación no compila release ni publica artefactos. El APK debug solo confirma que el código empaqueta correctamente durante el job y se descarta al terminar.

## Candidatos de prueba

El workflow `Android Candidate` es la única vía de CI que publica un APK descargable. Se activa de forma deliberada:

- manualmente con `workflow_dispatch`, una vez que el workflow esté disponible en la rama predeterminada; o
- creando una etiqueta con el patrón `candidate-*` sobre el commit exacto que se quiere probar.

Ejemplo de etiqueta: `candidate-20260830-1`.

El candidato ejecuta tests unitarios, lint y `assembleRelease`. Solo publica `gymup-v1-release`, con una retención de 7 días. No publica un APK debug.

Antes de que caduque el artefacto se debe descargar el APK que vaya a probarse y registrar su SHA-256 en la issue o PR de validación. Un APK no se identifica únicamente por su nombre o por el número visible de versión.

## Reglas de consumo

- No usar runners grandes o de pago.
- No restaurar ejecuciones duplicadas por `push` a ramas de trabajo.
- No subir informes o APK como artefactos salvo que sean necesarios para un candidato explícito.
- Mantener la caché estándar de Gradle porque reduce el tiempo de las ejecuciones sucesivas.
- No volver a ejecutar un job verde sin una causa trazada.
- La configuración de presupuesto o límite monetario pertenece a la cuenta de GitHub y no puede imponerse desde el repositorio.

## Validación física

GitHub Actions verifica el código, pero no sustituye la instalación y prueba del APK exacto en un dispositivo compatible. Los fallos o aprobaciones de validación física se registran en GitHub para conservar la trazabilidad.

