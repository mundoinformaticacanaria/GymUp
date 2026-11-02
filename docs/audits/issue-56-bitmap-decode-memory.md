# Issue #56 — memoria de decodificación de imágenes de ejercicios

Fecha: 2026-11-02. Estado: **CI JVM/Robolectric validada en HEAD anterior; pruebas Android API 35/36 pendientes**.

## Hallazgo

La galería de sesión y las miniaturas del catálogo utilizan el lector
`ExerciseImageManager.readBytes()` (límite de 10 MiB y comprobaciones de
identidad de rutas), pero `BitmapFactory.decodeByteArray` decodificaba la
resolución completa. Un PNG pequeño y muy compresible puede expandirse a un
bitmap enorme y agotar memoria aun cumpliendo el límite de bytes.

## Mitigación técnica

`decodeUserImageBitmap` delega en `decodeBoundedImageBytes`: primero lee
dimensiones con `inJustDecodeBounds` sin asignar el bitmap, después calcula
`inSampleSize` en potencias de dos para una arista máxima de 512 px y
rechaza un resultado que supere ese límite. No se cambia el límite de
10 MiB, el ZIP v1, Room ni el fichero original; se reduce solo la memoria
de las imágenes mostradas a 140dp/96dp.

`BoundedImageDecodeTest` aporta tres regresiones Robolectric: PNG de
2048×1024 decodificado a tamaño de miniatura, PNG pequeño sin reducción
y bytes malformados. No afirmar que la suite está verde hasta comprobar la
CI exacta del commit.

## Límites y puertas

La comprobación de dimensiones no prueba que todos los decodificadores
sean inmunes a imágenes malformadas o a picos transitorios de memoria.
Las protecciones por pathname/fileKey siguen siendo best-effort ante
TOCTOU/ABA. Continúan pendientes ejecución instrumentada Android 15/16,
durabilidad/cortes reales, auditoría DAO/lectores/procesos y regresión
física BF-02. Mantener #56 abierta y PR #57 DRAFT sobre #50. No fusionar,
publicar APK ni retirar `exercise-images-previous-*` por este cambio.

## CI del primer cambio y corrección posterior

[Android CI #41387156929](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41387156929) terminó **FAILURE** para `4864fdc`: 368 tests JVM, uno fallido (`BoundedImageDecodeTest.malformedImageIsNotRendered`, línea 49). El `BitmapFactory` de Robolectric puede devolver un bitmap simulado ante cuatro bytes arbitrarios; no se consideró validada la mitigación.

`3ecb6e8` rechaza antes del decoder los buffers menores de ocho bytes, que no pueden contener una imagen codificada válida; `1d3d616` amplía la regresión a entradas vacías y de siete bytes. Esta defensa mínima no sustituye una validación completa del formato y no prueba inmunidad frente a archivos malformados más largos. **Nueva CI pendiente de confirmar** para estos commits; sin AVD API 35/36 ni prueba física.

## Validación y fronteras de muestreo — 2026-11-02, 06:17 UTC

[Android CI #41392271830](https://github.com/mundoinformaticacanaria/GymUp/actions/runs/41392271830) terminó **SUCCESS** para el SHA exacto `71a1d50`: job `verify` con tests JVM/Robolectric, lint, ensamblado debug y compilación de fuentes androidTest. No se ejecutó un emulador ni un dispositivo y no se publicó APK.

El commit [`27015fd`](https://github.com/mundoinformaticacanaria/GymUp/commit/27015fd559fee940e290f02760079eedfc84c1a4) añade dos regresiones Robolectric: arista exactamente de 512 px sin reducción y arista de 513 px con muestreo a un máximo de 512 px. **CI de estas dos pruebas pendiente de comprobar**; no se atribuye el éxito anterior al nuevo commit. No hay cambios de comportamiento de producción ni de formato de backup.

Continúan sin resolver las pruebas instrumentadas Android 15/16, durabilidad/cortes reales, carreras TOCTOU y auditoría integral DAO/lectores/procesos. #56 y PR #57 deben permanecer abiertas/DRAFT.
