# #56 — Presupuesto total de descompresión ZIP (propuesta técnica, PR #57 DRAFT)

Fecha: 2026-11-02. Alcance: lectura de backups v1, sin cambiar su formato.

## Hallazgo estático

Antes de esta corrección, `BackupArchiveCodec.readAndValidate` limitaba el ZIP comprimido a 100 MiB, cada entrada descomprimida a 25 MiB y el número de entradas a 1.000, **pero no limitaba la suma descomprimida**. El lector guardaba todas las entradas en un `LinkedHashMap<String, ByteArray>` antes de comprobar el manifest. Un ZIP malicioso muy compresible, con muchas entradas individualmente válidas, podía retener una cantidad de memoria muy superior a 100 MiB y provocar presión de memoria u OOM antes de rechazarlo. No consta incidente real.

## Mitigación propuesta y pruebas

Se añade un presupuesto acumulado de **128 MiB descomprimidos** a la lectura, aplicado antes de materializar la entrada que rebasaría el total; se conserva el límite previo de 25 MiB por entrada y el de 100 MiB de ZIP comprimido. Dos tests JVM prueban que dos entradas individualmente pequeñas no pueden superar el presupuesto y que el límite exacto permite un ZIP válido, pero un byte menos lo rechaza. La prueba usa un límite inyectado pequeño para no reservar 128 MiB en CI.

**Compatibilidad por revisar antes del merge:** un ZIP v1 antes aceptado, comprimido a 100 MiB o menos pero con más de 128 MiB descomprimidos, ahora sería rechazado. `BackupArchiveCodec.create` aún podría generar uno de esos ZIP; por tanto, **no fusionar esta propuesta sin decidir y documentar un presupuesto de expansión compatible y la política simétrica de exportación**. Es un límite técnico propuesto, no una nueva regla funcional aprobada. La PR permanece DRAFT y #56 abierta.

Este límite no elimina todas las copias intermedias ni garantiza memoria suficiente en cualquier teléfono; no reemplaza la instrumentación API 35/36, la prueba física, la durabilidad ante cortes, la auditoría DAO/lectores/procesos ni la protección atómica TOCTOU.
