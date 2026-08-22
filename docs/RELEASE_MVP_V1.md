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

Esta firma **no es una firma de distribución comercial ni una identidad estable de actualización**. Los runners alojados de GitHub pueden generar una clave debug distinta en ejecuciones diferentes. Por tanto, el APK validado debe tratarse como un candidato instalable concreto, no como una cadena de actualizaciones firmadas entre runs de CI.

Antes de distribuir actualizaciones conservando la instalación y los datos debe introducirse una clave estable controlada por el propietario, almacenada fuera del repositorio, e incrementar `versionCode`. Para Google Play u otro canal público esa clave de producción es obligatoria.

## Instalación

En el teléfono Android 15 o superior:

1. Descargar `app-release.apk` desde el artefacto `gymup-v1-release` del run de CI validado.
2. Autorizar temporalmente la instalación de aplicaciones desconocidas para la aplicación desde la que se abre el APK, si Android lo solicita.
3. Abrir el APK y confirmar la instalación.

También puede instalarse mediante ADB:

```bash
adb install app-release.apk
```

Si ya existe una instalación firmada con otra clave, Android rechazará la actualización directa. En ese caso debe exportarse antes un backup de GymUp, desinstalar la versión anterior, instalar el nuevo APK e importar el backup.

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
