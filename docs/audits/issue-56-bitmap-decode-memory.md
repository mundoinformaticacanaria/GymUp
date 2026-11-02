# Issue #56 — memoria de decodificación de imágenes de ejercicios

Fecha: 2026-11-02. Estado: **pendiente de CI y pruebas Android API 35/36**.

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
