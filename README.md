# GymUp

GymUp es una aplicación Android offline para planificar, registrar y consultar sesiones de entrenamiento de gimnasio con el mínimo número de interacciones posible.

## Objetivo de producto

La v1 debe permitir:

- planificar sesiones en fechas pasadas, presentes o futuras;
- ejecutar una sesión registrando datos por serie;
- reutilizar rutinas maestras y duplicar listas de ejercicios de sesiones anteriores;
- consultar histórico de sesiones y evolución por ejercicio;
- generar un informe JSON por sesión realizada;
- exportar e importar una copia de seguridad completa de la aplicación;
- funcionar sin conexión a Internet.

La fuente de verdad funcional y técnica del proyecto es este repositorio.

## Documentación

- [Contrato funcional v1](docs/PRODUCT_CONTRACT_V1.md)
- [Cierre funcional MVP v1](docs/PRODUCT_CONTRACT_V1_CLOSURE.md)
- [Arquitectura técnica v1](docs/TECHNICAL_ARCHITECTURE_V1.md)
- [Modelo de datos v1](docs/DATA_MODEL_V1.md)
- [Estrategia de pruebas](docs/TEST_STRATEGY_V1.md)
- [Gobierno, roles y reglas de trabajo](docs/PROJECT_GOVERNANCE.md)
- [Política de ejecución continua](docs/EXECUTION_POLICY.md)

## Stack v1

- Android nativo, Kotlin 2.3.21.
- Jetpack Compose + Material 3.
- `minSdk 35`, `compileSdk/targetSdk 36`.
- Gradle 8.13 + AGP 8.13.2 + JDK 17.
- Room/SQLite para datos de entrenamiento.
- DataStore para preferencias pequeñas.
- Funcionamiento principal 100% offline.

## Build local

Requisitos:

- JDK 17.
- Android SDK 36 instalado.

Comandos principales:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

El APK debug se genera en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Estado

**Contrato funcional del MVP v1 cerrado el 20/08/2026.**

La arquitectura técnica v1 está definida y el desarrollo Android ha comenzado. El repositorio dispone de Gradle Wrapper y CI que ejecuta tests, lint y ensamblado de APK.

El backlog de implementación del MVP está organizado en las Issues #3–#8. El Tech Lead continúa automáticamente con la siguiente Issue desbloqueada y solo se detiene ante un bloqueo real que requiera al propietario.

No deben implementarse funcionalidades que contradigan el contrato funcional vigente. Cualquier cambio futuro de producto debe quedar trazado explícitamente en GitHub antes de incorporarse al desarrollo.
