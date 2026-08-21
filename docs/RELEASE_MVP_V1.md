# GymUp MVP v1 — entrega e instalación manual

## Versión

- `versionName`: `1.0.0`
- `versionCode`: `1`
- Android mínimo: 15 (`minSdk 35`)
- Android objetivo: 16 (`targetSdk 36`)
- Distribución v1: APK instalada manualmente.

## APK de entrega

El CI genera y publica como artefacto:

- `gymup-v1-release` → `app-release.apk`

La variante `release` se firma para este MVP con la configuración debug estándar de Android. Esto permite instalar el APK directamente sin almacenar una clave de producción en GitHub.

Esta firma **no es una firma de distribución comercial**. Antes de publicar en Google Play u otro canal público debe crearse una clave de producción controlada por el propietario, conservarla fuera del repositorio e incrementar `versionCode`.

## Instalación

En el teléfono Android 15 o superior:

1. Descargar `app-release.apk` desde el artefacto `gymup-v1-release` del run de CI validado.
2. Autorizar temporalmente la instalación de aplicaciones desconocidas para la aplicación desde la que se abre el APK, si Android lo solicita.
3. Abrir el APK y confirmar la instalación.

También puede instalarse mediante ADB:

```bash
adb install -r app-release.apk
```

## Actualizaciones del APK v1

Mientras se conserve la misma firma y se incremente `versionCode`, Android puede actualizar la instalación existente sin borrar los datos locales. No debe cambiarse la estrategia de firma de una instalación ya distribuida sin planificar una migración/reinstalación.

## Datos y copia de seguridad

GymUp funciona de forma local y offline. No existe sincronización en la nube en v1.

Antes de reinstalar, cambiar de dispositivo, realizar una limpieza histórica importante o probar una versión incompatible:

1. Abrir **Ajustes**.
2. Exportar una **copia de seguridad completa**.
3. Guardar el ZIP en un lugar externo al almacenamiento privado de la app.

El ZIP de backup contiene los datos de GymUp y las imágenes personalizadas necesarias. No incorpora contraseña ni cifrado propio; debe custodiarse como una copia personal.

La importación de backup es `replace-all`: valida el archivo completo antes de reemplazar los datos existentes.

## Validación de entrega

Un APK solo se considera candidato de entrega cuando el mismo SHA ha superado en GitHub Actions:

- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- `assembleRelease`
- publicación de los artefactos debug y release.

El PR de cierre del MVP debe estar sincronizado con `main`, sin hilos de revisión pendientes y con el CI del SHA final completamente verde antes del merge.
