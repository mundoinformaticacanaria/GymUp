# GymUp

GymUp es una aplicación Android offline para planificar, registrar y consultar sesiones de entrenamiento de gimnasio con el mínimo número de interacciones posible.

## MVP v1

GymUp v1 permite:

- planificar sesiones en fechas pasadas, presentes o futuras;
- ejecutar una sesión registrando objetivo y datos reales por serie;
- reutilizar rutinas maestras y duplicar sesiones anteriores;
- mantener ejercicios, grupos musculares, equipos y tipos de sesión;
- añadir hasta 3 imágenes personalizadas por ejercicio;
- consultar histórico de sesiones y evolución por ejercicio;
- generar y compartir un informe JSON por sesión realizada;
- exportar e importar una copia de seguridad completa;
- eliminar histórico anterior a una fecha con confirmación fuerte;
- funcionar sin conexión a Internet.

La fuente de verdad funcional y técnica del proyecto es este repositorio.

## Documentación

- [Contrato funcional v1](docs/PRODUCT_CONTRACT_V1.md)
- [Cierre funcional MVP v1](docs/PRODUCT_CONTRACT_V1_CLOSURE.md)
- [Arquitectura técnica v1](docs/TECHNICAL_ARCHITECTURE_V1.md)
- [Flujo UX de rutinas v1](docs/ROUTINES_UX_FLOW_V1.md)
- [Auditoría de crecimiento vertical v1](docs/UX_VERTICAL_AUDIT_V1.md)
- [Modelo de datos v1](docs/DATA_MODEL_V1.md)
- [Estrategia de pruebas](docs/TEST_STRATEGY_V1.md)
- [Entrega e instalación manual del MVP](docs/RELEASE_MVP_V1.md)
- [Política de uso de GitHub Actions](docs/CI_USAGE_POLICY.md)
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

Validación principal:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
./gradlew assembleRelease
```

APKs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

El CI habitual verifica el código sin conservar APK. Solo el workflow explícito `Android Candidate` publica `gymup-v1-release` durante 7 días.

## Estado

**Contrato funcional del MVP v1 cerrado el 20/08/2026.**

La versión de aplicación preparada para el cierre del MVP es `1.0.0` (`versionCode 1`). La distribución inicial es manual mediante APK; Google Play, autenticación y sincronización multidispositivo quedan fuera de v1.

No deben implementarse funcionalidades que contradigan el contrato funcional vigente. Cualquier cambio futuro de producto debe quedar trazado explícitamente en GitHub antes de incorporarse al desarrollo.

