# #56 — Presupuesto total de descompresión ZIP (propuesta técnica, PR #57 DRAFT)

Fecha: 2026-11-02. Alcance: lectura de backups v1, sin cambiar su formato.

## Hallazgo estático

Antes de esta corrección, `BackupArchiveCodec.readAndValidate` limitaba el ZIP comprimido a 100 MiB, cada entrada descomprimida a 25 MiB y el número de entradas a 1.000, **pero no limitaba la suma descomprimida**. El lector guardaba todas las entradas en un `LinkedHashMap<String, ByteArray>` antes de comprobar el manifest. Un ZIP malicioso muy compresible, con muchas entradas individualmente válidas, podía retener una cantidad de memoria muy superior a 100 MiB y provocar presión de memoria u OOM antes de rechazarlo. No consta incidente real.

## Mitigación propuesta y pruebas

Se añade un presupuesto acumulado de **128 MiB descomprimidos** a la lectura, aplicado antes de materializar la entrada que rebasaría el total; se conserva el límite previo de 25 MiB por entrada y el de 100 MiB de ZIP comprimido. Dos tests JVM prueban que dos entradas individualmente pequeñas no pueden superar el presupuesto y que el límite exacto permite un ZIP válido, pero un byte menos lo rechaza. La prueba usa un límite inyectado pequeño para no reservar 128 MiB en CI.

**Compatibilidad por revisar antes del merge:** un ZIP v1 antes aceptado, comprimido a 100 MiB o menos pero con más de 128 MiB descomprimidos, ahora sería rechazado. `BackupArchiveCodec.create` aún podría generar uno de esos ZIP; por tanto, **no fusionar esta propuesta sin decidir y documentar un presupuesto de expansión compatible y la política simétrica de exportación**. Es un límite técnico propuesto, no una nueva regla funcional aprobada. La PR permanece DRAFT y #56 abierta.

Este límite no elimina todas las copias intermedias ni garantiza memoria suficiente en cualquier teléfono; no reemplaza la instrumentación API 35/36, la prueba física, la durabilidad ante cortes, la auditoría DAO/lectores/procesos ni la protección atómica TOCTOU.

## Auditoría de simetría exportación/importación — 2026-11-02

Inspección estática de `BackupArchiveCodec.create`, `writeEntry` y
`readAndValidateWithExpandedLimit` en el HEAD `4392211` de PR #57:

| Presupuesto aplicado por el lector | Exportador actual | Consecuencia |
| --- | --- | --- |
| ZIP comprimido ≤ 100 MiB | `ByteArrayOutputStream` sin comprobación final de longitud | Puede exportar un ZIP que su propio lector rechaza |
| Cada entrada descomprimida ≤ 25 MiB | `writeEntry` no comprueba `bytes.size` | Puede exportar `data.json` u otra entrada que no pueda importar |
| Suma descomprimida ≤ 128 MiB (**propuesta no aprobada**) | No comprueba suma de manifest, datos e imágenes | Puede exportar ZIP comprimido pequeño pero demasiado expandido |
| Número de entradas ≤ 1.000 | `create` escribe 2 entradas fijas más una por imagen sin límite | Puede exportar más de 998 imágenes y superar el límite del lector |

La cuarta asimetría (número de entradas) también se deriva del código y no
estaba enumerada en el comentario inicial de #58. Se trata de una
**incompatibilidad potencial**, no de un fallo observado en datos del usuario.
Además, `create` construye el ZIP completo en memoria, por lo que aplicar
un límite únicamente al resultado final no evita el pico de memoria durante
la compresión. La comprobación previa de entradas y bytes y un escritor
acotado serían necesarios para reducir ese riesgo.

**Puerta de producto:** #58 solicita al PROPIETARIO decidir la política de
128 MiB y compatibilidad de backups previos. Hasta recibir respuesta, no
modificar límites de importación/exportación, no dar por válida la simetría y
no fusionar PR #57. Los límites actuales de 100 MiB, 25 MiB y 1.000 entradas
ya existen en el lector; tampoco deben cambiarse por inferencia.

**Matriz de pruebas a ejecutar tras la decisión:** round-trip válido en
frontera; entrada individual justo dentro/fuera de 25 MiB; ZIP comprimido
justo dentro/fuera de 100 MiB; suma justo dentro/fuera de la cifra aprobada;
998/999 imágenes (2 entradas fijas); ZIP muy compresible; rechazo controlado
sin éxito falso de exportación; y compatibilidad de ZIP v1 anteriores con
imágenes y sesiones reales de prueba. Usar límites inyectables pequeños en
JVM cuando sea posible, sin asignar cientos de MiB en CI. La instrumentación
Android 15/16, la durabilidad y la regresión BF-02 siguen siendo gates
independientes.
