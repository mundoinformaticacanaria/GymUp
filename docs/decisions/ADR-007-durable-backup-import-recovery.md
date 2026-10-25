# ADR-007 (propuesta) — Recuperación duradera de importación replace-all

Estado: **PROPUESTO / NO IMPLEMENTADO**  
Fecha: 2026-10-25  
Issue: #56 · PR: #57  
Relacionado: ADR-005 y contrato funcional v1 §17

## Problema y límite actual

Room, el directorio privado de imágenes y DataStore no comparten una transacción atómica. PR #57 mitiga excepciones y cancelaciones controladas manteniendo abierta la transacción Room y conservando imágenes anteriores. Su CI verde **no demuestra recuperación tras terminación abrupta**: un proceso puede morir después de conmutar imágenes, escribir tema o confirmar Room y antes de limpiar temporales.

El objetivo es garantizar que, al reabrir la app tras una interrupción, el usuario encuentre un estado completo anterior o nuevo, nunca una combinación silenciosa de ambos. Se mantiene ZIP v1, importación completa replace-all, almacenamiento offline y sin servicios externos.

## Protocolo técnico propuesto (pendiente de implementación y pruebas)

1. **Exclusión mutua**: impedir dos importaciones simultáneas y escrituras de dominio concurrentes durante la ventana de reemplazo. Exportar una instantánea consistente del estado previo (Room + imágenes de usuario + tema) y validar sus referencias. Si no puede obtenerse, abortar antes de modificar datos.
2. **Journal privado duradero**: escribir la instantánea anterior en un directorio exclusivo por operación con manifiesto, checksums y versión del protocolo. Crear una marca `PREPARED` de forma atómica **solo después** de persistir y verificar todos los archivos; sincronizar archivos y metadatos de directorio según las garantías reales de Android. Nunca almacenar contenido personal en logs.
3. **Mutación**: tras `PREPARED`, aplicar el reemplazo de Room, imágenes y DataStore bajo la secuencia reversible. Mantener el journal intacto durante toda la operación. La finalización exitosa requiere persistencia confirmada de los tres almacenes.
4. **Punto de confirmación**: retirar/desactivar la marca de recuperación **solo cuando** el nuevo estado completo sea duradero. La limpieza de copias antiguas puede continuar después, de manera idempotente. Si el proceso muere antes del punto de confirmación, la operación se considera no completada y se recupera el estado anterior.
5. **Recuperación de arranque obligatoria**: antes de exponer repositorios/pantallas capaces de leer o escribir datos, comprobar journal pendiente. Si existe, validar la copia anterior y **restaurarla íntegramente**, incluyendo Room, imágenes y tema; después verificar y retirar la marca. Si la recuperación se interrumpe, el journal sigue disponible y el siguiente arranque reintenta. No invocar la ruta normal de importación recursivamente ni eliminar la única copia anterior.
6. **Fallo de recuperación**: bloquear operaciones de dominio que pudieran sobrescribir el estado pendiente; mostrar error recuperable y conservar los archivos. No declarar éxito ni borrar temporales por comodidad.

### Requisitos de diseño antes de implementar

- Verificar si las APIs de escritura/sincronización de Android disponibles permiten el orden de persistencia propuesto; no equiparar `renameTo` por sí solo con durabilidad tras corte de energía.
- Verificar que la captura de Room, imágenes y preferencias sea consistente bajo exclusión de escrituras; documentar el punto de inicio de bloqueo y cómo se libera ante cancelación.
- Evitar usar nombres de carpetas `previous-*` como única fuente de verdad: son ambiguos tras varias operaciones y no prueban si Room confirmó.
- Mantener compatibilidad de journal mediante versión explícita y política de fallo cerrado ante versión desconocida.

## Matriz mínima de pruebas exigida

| Punto de interrupción | Estado esperado al reabrir |
| --- | --- |
| Antes de crear `PREPARED` | Estado anterior intacto; temporales descartables |
| Después de `PREPARED`, antes de modificar Room | Estado anterior íntegro; journal consumido tras recuperación |
| Tras activar imágenes, antes de confirmar Room | Estado anterior íntegro (Room, imágenes y tema) |
| Tras escribir DataStore, antes de confirmar Room | Estado anterior íntegro |
| Tras confirmar Room, antes del punto de confirmación | Estado anterior íntegro (restauración desde journal) |
| Después del punto de confirmación, antes de limpiar temporales | Estado nuevo íntegro; limpieza reintentable |
| Fallo o segunda interrupción durante recuperación | Journal conservado; recuperación idempotente en siguiente arranque |
| Journal incompleto, checksum incorrecto o almacenamiento insuficiente | Fallo cerrado, sin sustitución destructiva ni eliminación de la única copia |

Implementar tests Robolectric con puntos de corte deterministas y reconstrucción del componente (simulación de reinicio); añadir prueba instrumentada/dispositivo para arranque real y restauración física. Una excepción inyectada y capturada dentro del mismo proceso **no simula** una terminación abrupta.

## Gates y alcance

Esta ADR es **propuesta técnica**, no evidencia de implementación. No cambiar el contrato de backup, no modificar el APK BF-02 probado y no fusionar PR #57 ni cerrar #56 hasta implementar, verificar CI y resolver la validación física aplicable. La autorización de un candidato APK sigue siendo exclusiva del propietario conforme a #35.
