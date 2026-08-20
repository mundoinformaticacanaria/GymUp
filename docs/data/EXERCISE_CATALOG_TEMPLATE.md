# GymUp — Plantilla de catálogo inicial de ejercicios

Estado: **vigente para entrega FUNCIONAL**

Esta plantilla define el formato que debe usar FUNCIONAL para entregar el catálogo inicial de aproximadamente 60–100 ejercicios de GymUp v1.

El objetivo es que el contenido pueda validarse de forma automática y transformarse después en datos seed de la aplicación sin reinterpretaciones manuales.

## 1. Formato de entrega

Entregar un único archivo JSON UTF-8 con esta estructura:

```json
{
  "schema_version": 1,
  "exercises": [
    {
      "nombre_es": "Press de banca con barra",
      "nombre_en": "Barbell Bench Press",
      "grupo_muscular": "Pecho",
      "equipo": "Barra",
      "modalidad_carga": "KG_TOTAL",
      "unidad_medicion": "REPETICIONES",
      "rir_obligatorio": true,
      "series_iniciales": null,
      "carga_inicial": null,
      "medicion_inicial": null,
      "descripcion": "Press horizontal con barra realizado en banco.",
      "imagenes": []
    }
  ]
}
```

## 2. Campos

### Obligatorios

- `nombre_es`: texto no vacío.
- `nombre_en`: texto no vacío.
- `grupo_muscular`: debe coincidir exactamente con un valor activo del catálogo inicial aprobado.
- `modalidad_carga`: uno de los valores cerrados indicados abajo.
- `unidad_medicion`: uno de los valores cerrados indicados abajo.
- `rir_obligatorio`: booleano.
- `imagenes`: array, puede estar vacío.

### Opcionales

- `equipo`: texto de catálogo o `null`.
- `series_iniciales`: entero positivo o `null`.
- `carga_inicial`: número >= 0 con hasta 2 decimales o `null`.
- `medicion_inicial`: entero >= 0 o `null`.
- `descripcion`: texto breve o `null`.

No se deben inventar objetivos iniciales solo para rellenar campos. Si no existe un default funcional razonable, usar `null`.

## 3. Valores de `modalidad_carga`

- `KG_TOTAL`
- `KG_POR_MANO`
- `KG_POR_LADO`
- `PESO_CORPORAL`
- `PESO_CORPORAL_LASTRE`
- `PESO_CORPORAL_ASISTENCIA`
- `SIN_PESO`

Para `PESO_CORPORAL_LASTRE` y `PESO_CORPORAL_ASISTENCIA`, `carga_inicial` representa únicamente el valor adicional X; nunca el peso corporal total.

Para `PESO_CORPORAL` y `SIN_PESO`, `carga_inicial` debe ser `null`.

## 4. Valores de `unidad_medicion`

- `REPETICIONES`
- `REPETICIONES_LADO`
- `SEGUNDOS`
- `SEGUNDOS_LADO`

`medicion_inicial` es siempre un entero cuando existe.

## 5. Imágenes

Un ejercicio puede tener de 0 a 3 imágenes.

Si no se aporta una imagen legalmente utilizable, usar:

```json
"imagenes": []
```

Cuando se aporte una imagen, cada elemento debe incluir metadatos suficientes para auditar su uso:

```json
{
  "archivo": "press_banca_01.webp",
  "fuente": "https://...",
  "autor": "Nombre o entidad",
  "licencia": "CC BY 4.0"
}
```

Reglas:

- `archivo`: nombre de archivo propuesto, sin ruta absoluta.
- `fuente`: URL de origen o `null` si es recurso propio.
- `autor`: autor/entidad o `null` si no aplica.
- `licencia`: licencia explícita o `PROPIO`.
- no incluir recursos cuya licencia sea desconocida o incompatible;
- la aplicación empaquetará los recursos aprobados para uso offline; no dependerá de la URL en ejecución.

## 6. Reglas de calidad y validación

- Objetivo aproximado: 60–100 ejercicios útiles y comunes, no un catálogo masivo.
- `nombre_es` y `nombre_en` deben ser coherentes y referirse al mismo ejercicio.
- No se admiten nombres duplicados según la normalización de GymUp: se ignoran mayúsculas/minúsculas y tildes/diacríticos.
- No crear variantes duplicadas solo por idioma.
- Cada ejercicio tiene un único grupo muscular principal en v1.
- `equipo` es opcional.
- Los objetivos iniciales son defaults, no prescripciones; pueden quedar vacíos.
- Las imágenes son deseables pero opcionales.

## 7. Entrega

FUNCIONAL debe adjuntar o incorporar el JSON siguiendo exactamente esta plantilla en la Issue de cierre funcional o en una Issue específica enlazada desde ella.

El Tech Lead validará estructura, duplicados, enums, coherencia con catálogos maestros y licencias antes de aceptar el dataset como seed de v1.
