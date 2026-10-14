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

- [Instrucciones operativas para agentes](AGENTS.md)
- [Guía rápida de usuario](docs/GUIA_RAPIDA_USUARIO.md)
- [Flujo UX de rutinas](docs/ROUTINES_UX_FLOW_V1.md)
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

La aplicación Android está en evolución mediante Issues, ramas y PR revisables. La fuente de verdad operativa es el conjunto de Issues abiertas y sus labels; la coordinación del bloque funcional y del siguiente candidato se mantiene en la [Issue #35](../../issues/35).

Android CI verifica tests JVM/Robolectric, lint y ensamblado debug en PR con código. La verificación ordinaria no publica APK. Los candidatos de prueba se agrupan por bloque funcional, requieren autorización expresa del Product Owner y se generan únicamente mediante el workflow específico.

El Tech Lead continúa con la siguiente Issue independiente y desbloqueada conforme a `docs/EXECUTION_POLICY.md`. Puede fusionar cambios tras superar sus puertas técnicas y funcionales; solo pide autorización si el merge activa consumo facturable/cuota limitada de GitHub o tokens facturados. No se implementan funcionalidades que contradigan el contrato vigente.

## Integración BF-02

La integración acumulada del MVP y BF-02 incorpora el tema, el flujo de rutinas y las mejoras de ejecución probadas por el propietario. El candidato de referencia es el run `38438326886`, commit `d837d9e`; las pruebas guiadas y sus límites están documentados en [la Issue #35](https://github.com/mundoinformaticacanaria/GymUp/issues/35#issuecomment-6151717358). Esta integración conserva la política de CI y logs de `main`; no incluye las PR #47–#49 ni declara cerrado el MVP #8. Las comprobaciones restantes y la regresión global siguen sus puertas propias.
