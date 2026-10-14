# ADR-006 — Stack Android y arquitectura base

Estado: **Aceptado**  
Fecha: 2026-08-20  
Issue: #2

## Contexto

GymUp v1 es una aplicación Android monousuario, offline, orientada a teléfono en vertical, con `minSdk 35` y Android 16 como dispositivo principal inicial. El proyecto debe usar únicamente tecnología gratuita/libre y minimizar riesgo operativo y complejidad accidental.

El contrato funcional ya está cerrado. La arquitectura debe permitir implementar sesiones, maestros, rutinas, histórico, gráficas, exportación JSON, backup ZIP e imágenes locales sin introducir backend ni servicios remotos.

## Decisión

### Toolchain

- Android nativo.
- Kotlin `2.3.21`.
- Android Gradle Plugin `8.13.2`.
- Gradle `8.13`.
- JDK `17`.
- `compileSdk = 36`.
- `targetSdk = 36`.
- `minSdk = 35`.

Se fija API 36 deliberadamente para el MVP: cubre Android 15+ y el dispositivo Android 16 objetivo sin adoptar API 37/AGP 9 antes de que exista una necesidad funcional.

### UI

- Jetpack Compose.
- Compose BOM estable `2026.06.01`.
- Material 3.
- Activity Compose.
- Navigation Compose.
- Diseño edge-to-edge y soporte de light/dark/system.

### Estado y concurrencia

- `ViewModel` + `StateFlow` para estado observable de pantalla.
- Coroutines/Flow para operaciones asíncronas.
- Estado de UI inmutable; eventos de usuario explícitos.
- Operaciones de persistencia fuera del main thread.

### Persistencia y preferencias

- Room `2.8.4` sobre SQLite.
- KSP2 `2.3.9` para generación de Room.
- DataStore Preferences `1.2.1` para preferencias pequeñas de usuario (tema y futuras preferencias no relacionales).
- `kotlinx.serialization-json` `1.11.0` para formatos versionados de informe y backup.

### Inyección de dependencias

No se incorpora Hilt/Dagger en v1. Se usa composición manual mediante `GymUpApplication` + `AppContainer`, con dependencias por constructor en repositorios/use cases/ViewModels.

Motivo: un único módulo Android y un único proceso no justifican el coste de generación/configuración adicional. La interfaz de repositorios mantiene testabilidad y permite introducir DI automático más adelante sin cambiar el dominio.

### Modularización

El MVP empieza con **un único módulo Gradle `:app`** organizado por capas y feature packages.

Estructura objetivo:

```text
com.mundoinformaticacanaria.gymup
├── app/
├── core/
│   ├── model/
│   ├── util/
│   └── ui/
├── data/
│   ├── local/
│   ├── repository/
│   ├── export/
│   └── image/
├── domain/
│   ├── repository/
│   └── usecase/
└── feature/
    ├── home/
    ├── sessions/
    ├── routines/
    ├── exercises/
    ├── history/
    ├── charts/
    └── settings/
```

La separación multi-módulo se pospone hasta que exista una razón medible (tiempos de build, ownership o reutilización).

### Navegación

Se usa una actividad única (`MainActivity`) con Navigation Compose. Las pantallas reciben IDs/argumentos simples y cargan datos desde repositorios; no se pasan entidades completas entre destinos.

### Gráficas

Para v1 se evita una dependencia de charting pesada. Las gráficas históricas se dibujarán con Compose Canvas y primitivas propias porque:

- el alcance es limitado (líneas/puntos, hasta 10 ejecuciones);
- se necesita cortar series por modalidad/unidad;
- reduce dependencia externa y facilita accesibilidad/semántica controlada.

### Imágenes

- Imágenes seed empaquetadas como recursos de app cuando existan.
- Imágenes personalizadas copiadas al almacenamiento privado de la app.
- No se persisten rutas absolutas externas como fuente primaria.
- Selección mediante Android Photo Picker/SAF según disponibilidad; sin permiso de almacenamiento amplio.

## Consecuencias

### Positivas

- Stack estable y alineado con Android 16.
- Menor superficie de dependencias.
- Arquitectura suficiente para pruebas y evolución sin sobre-modularización.
- Totalmente offline.
- Evita adoptar AGP 9/API 37 sin necesidad de producto.

### Costes

- La composición manual requiere disciplina en `AppContainer`.
- Las gráficas propias requieren implementar escalado, ejes y accesibilidad.
- Un solo módulo puede necesitar partición futura si el proyecto crece mucho.

## Alternativas descartadas

- **AGP 9.x + API 37:** no aporta capacidad requerida por v1 y eleva riesgo de compatibilidad KSP/Room.
- **Flutter/React Native:** añaden otra capa de runtime/tooling sin beneficio para una app exclusivamente Android.
- **Hilt desde el inicio:** sobrecoste innecesario para el tamaño actual.
- **Arquitectura multi-módulo temprana:** complejidad de Gradle sin beneficio demostrable.
- **Backend/sincronización:** fuera del contrato v1.
