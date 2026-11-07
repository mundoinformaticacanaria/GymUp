# ADR-008 — Informes, backup e imágenes

Estado: **Aceptado**  
Fecha: 2026-08-20  
Issue: #2

## Contexto

GymUp v1 debe exportar un JSON independiente por sesión realizada, permitir guardarlo/compartirlo con mecanismos estándar Android y ofrecer backup completo reimportable que reemplace todos los datos existentes. También admite imágenes opcionales de ejercicios y debe funcionar 100% offline.

## Decisión

### JSON de sesión

- Formato JSON UTF-8.
- `schema_version = 1` en raíz.
- Identificadores UUID estables.
- Contiene snapshots visibles además de IDs internos.
- Se exportan explícitamente objetivo y real; nunca se deduce trabajo no ejecutado.
- Solo sesiones con estado operativo `REALIZED` pueden exportarse.
- El nombre de archivo se deriva de `YYYY-MM-DD_<nombre-visible>.json` y se sanea técnicamente.
- Guardar: Android Storage Access Framework (`ACTION_CREATE_DOCUMENT`).
- Compartir: `FileProvider`/content URI y Android Sharesheet.

### Backup completo

Un backup v1 es un ZIP UTF-8 sin contraseña/cifrado adicional propio de GymUp.

Estructura conceptual:

```text
gymup-backup-YYYYMMDD-HHmmss.zip
├── manifest.json
├── data.json
└── images/
    └── <uuid>.<ext>
```

`manifest.json` incluye:

- `format = "gymup-backup"`
- `schema_version`
- versión de app exportadora
- fecha técnica de exportación
- checksum SHA-256 de `data.json`
- inventario de imágenes con checksum SHA-256, tamaño y nombre lógico

`data.json` contiene todos los datos maestros y transaccionales necesarios para reconstruir la app, con IDs estables y snapshots.

### Importación replace-all

La importación se divide en fases:

1. Copiar ZIP seleccionado a un área temporal privada.
2. Validar ZIP, rutas, tamaños, manifest, versión compatible y checksums.
3. Parsear completamente JSON a un modelo intermedio y validar invariantes básicas antes de tocar datos actuales.
4. Preparar imágenes en directorio temporal, rechazando entradas con path traversal o nombres inválidos.
5. Crear un backup de seguridad temporal interno de la base actual durante la operación de reemplazo cuando sea técnicamente viable.
6. Reemplazar datos Room en una única transacción lógica.
7. Conmutar directorio de imágenes de forma controlada.
8. Si falla una fase posterior, restaurar el estado anterior o abortar antes de hacerlo visible.
9. Limpiar temporales.

Nunca se hace merge v1.

### Compatibilidad

- Se acepta automáticamente `schema_version == 1`.
- Versiones futuras deberán aportar migrador de backup explícito antes de aceptar esquemas antiguos/nuevos.
- Un backup con versión desconocida no modifica la base actual.

### Imágenes seed

- Opcionales, 0–3 por ejercicio.
- Solo recursos con licencia compatible verificable.
- Empaquetadas en recursos/assets de la app para uso offline.
- Los metadatos de licencia se conservan en documentación/dataset.

### Imágenes personalizadas

- Se seleccionan con Photo Picker/SAF.
- GymUp copia el contenido a almacenamiento privado (`filesDir/exercise-images/`).
- El nombre físico se basa en UUID, no en nombre original del usuario.
- Se valida MIME/extensión y se aplica límite razonable de tamaño por imagen durante implementación.
- La base guarda una referencia lógica/relativa, nunca una ruta externa absoluta como fuente primaria.
- Eliminar una imagen personalizada elimina su fichero cuando ya no está referenciado.

### Privacidad

- No hay subida automática ni telemetría de imágenes/backups.
- La UI de backup advierte que el ZIP contiene datos personales de GymUp y debe custodiarse.
- No se registran en logs los contenidos JSON importados ni rutas sensibles completas.

## Consecuencias

- Los backups son auditables e íntegros sin depender de servicios remotos.
- Los checksums detectan corrupción antes del replace-all.
- Copiar imágenes al almacenamiento privado evita depender de permisos/URIs revocados.
- La importación requiere más código que copiar directamente una SQLite, pero desacopla backup de detalles internos y permite migraciones futuras controladas.
