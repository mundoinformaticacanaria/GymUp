# #56 — Protocolo de verificación de recuperación (pendiente de ejecución)

Estado: plan técnico, **no evidencia de pruebas superadas**. Rama de PR #57; no modifica el contrato, el ZIP v1 ni los datos del usuario.

## Entorno y aislamiento

- Ejecutar exclusivamente con datos sintéticos en dos AVD desechables: Android 15/API 35 y Android 16/API 36; no usar el dispositivo del propietario ni su backup.
- Conservar copia inmutable del ZIP original de prueba, su SHA-256 y una exportación base antes de cada caso. Crear un AVD limpio o restaurar snapshot de prueba entre casos.
- No conectar a producción el limpiador de staging ni borrar `exercise-images-previous-*`. No generar candidato APK, tag ni release.
- Registrar commit exacto de PR #57, versión Gradle/AGP, API, run de CI y resultado por caso. `assembleAndroidTest` no equivale a ejecutar `connectedAndroidTest`.

## Matriz mínima de casos

| Caso | Punto de interrupción / fallo | Invariantes que se deben comprobar |
| --- | --- | --- |
| A | ZIP inválido, UTF-8 inválido o checksum erróneo antes de preparar | Room, preferencias e imágenes idénticos a preimagen; error visible |
| B | Fallo inyectado durante preparación de staging | Sin mutación visible de Room/tema/imágenes; temporales recuperables |
| C | Fallo al activar imágenes antes de finalizar la transacción Room | Preimagen completa restaurada, sin éxito falso |
| D | Fallo inyectado al persistir tema/DataStore | Room, tema e imágenes coherentes con la preimagen; recuperación verificable |
| E | Terminación abrupta del proceso tras crear journal y antes de conmutar | Tras relanzar, recuperar preimagen o completar de forma coherente según protocolo documentado |
| F | Terminación abrupta durante/tras conmutación de imágenes | Tras relanzar, ninguna referencia USER apunta a bytes ausentes o a una mezcla de generaciones |
| G | Repetir relanzamiento dos veces tras E/F | Recuperación idempotente; no borrar preimagen antes de comprobar integridad |
| H | Sustituir raíz o imagen por symlink/archivo regular entre comprobación y uso | Fallar cerrado, sin leer/escribir fuera de `filesDir`; verificar límites de TOCTOU |
| I | Importación exitosa y exportación inmediata | Sesiones, maestros, tema e imágenes consistentes; exportación reimportable |

## Evidencia y gates

Para cada caso adjuntar resultado PASS/FAIL/SKIP, razón de SKIP, SHA del commit, API, salida de instrumentación y comparación de IDs/contenidos de Room, tema y SHA-256 de cada imagen. Para cortes abruptos, no confundir excepción capturada con muerte real del proceso: documentar el mecanismo de terminación y la fase confirmada por el journal. Verificar también el manifiesto **fusionado** y los accesos DAO/lectores fuera del importador.

No marcar #56 resuelta por CI JVM/Robolectric, por compilación de androidTest o por inspección estática. Mantener PR #57 en DRAFT hasta instrumentación, pruebas de cortes/durabilidad, auditoría de rutas y regresión física BF-02 (#8). La prueba física y autorización de candidato corresponden al PROPIETARIO (#35); el límite ZIP de #58 sigue siendo decisión funcional separada.
