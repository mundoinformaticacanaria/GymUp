# GymUp — Gobierno del proyecto y roles

## 1. Fuente de verdad

GitHub es la fuente de verdad del proyecto GymUp.

El contexto funcional, técnico y operativo necesario para trabajar debe mantenerse actualizado en el repositorio.

Las conversaciones externas pueden originar decisiones, pero una decisión que afecte al producto o a la implementación debe quedar reflejada en GitHub antes de depender de ella de forma estable.

Documentos principales:

- `README.md`: visión y estado general.
- `docs/PRODUCT_CONTRACT_V1.md`: contrato funcional vigente.
- ADRs futuros: decisiones técnicas/arquitectónicas relevantes.
- Issues: unidad de trabajo y trazabilidad de cambios.

## 2. Autoridad y roles actuales

### Product Owner — usuario

Responsabilidades:

- decidir alcance y comportamiento de producto;
- resolver ambigüedades funcionales;
- decidir ante contradicciones o cambios de requisitos;
- aportar datos iniciales cuando sea necesario;
- aprobar decisiones que alteren experiencia, reglas de negocio o alcance.

### Tech Lead — ChatGPT

Responsabilidades:

- dirigir técnicamente el proyecto;
- ejercer la máxima responsabilidad sobre el gobierno, orden, coherencia y administración operativa del GitHub del proyecto;
- decidir cuándo una Issue está suficientemente resuelta para cerrarse y efectuar su cierre cuando corresponda;
- elegir stack, arquitectura, librerías y estrategia de pruebas dentro de las restricciones acordadas;
- mantener coherencia entre requisitos, modelo y código;
- detectar contradicciones, riesgos y ambigüedades;
- detenerse cuando una decisión sea de producto o tenga impacto funcional relevante;
- mantener GitHub actualizado como fuente de verdad;
- preparar Issues suficientemente autocontenidas para que un futuro colaborador pueda trabajar sin depender del chat;
- revisar cambios, PRs y CI cuando exista desarrollo;
- priorizar software libre y gratuito conforme a la restricción del proyecto.

Actualmente el equipo de ejecución consta únicamente del Tech Lead. Si el proyecto crece, se podrán introducir roles adicionales.

## 3. Posibles roles futuros

Estos roles no están activos todavía, pero la documentación y las Issues deben permitir incorporarlos sin reconstruir contexto.

### Android Developer

- implementación de UI y dominio Android;
- persistencia local;
- importación/exportación;
- pruebas unitarias y de integración.

### UX/UI

- flujos de uso;
- ergonomía durante entrenamiento;
- accesibilidad;
- diseño claro/oscuro y escalado de texto.

### QA

- estrategia de pruebas funcionales;
- regresión;
- validación de importación/exportación;
- casos límite del histórico y precargas temporales.

### Data/Analytics

- evolución futura de informes;
- explotación estructurada de JSON;
- métricas derivadas si se incorporan en versiones posteriores.

## 4. Regla de decisión

El Tech Lead puede decidir sin consulta previa cuestiones puramente técnicas que no cambien el comportamiento de producto, siempre que:

- usen software libre/gratuito;
- respeten el contrato funcional;
- no introduzcan costes recurrentes obligatorios;
- no creen dependencia innecesaria de servicios propietarios;
- no comprometan privacidad, integridad de datos o capacidad offline.

Debe detenerse y consultar al Product Owner cuando exista:

- ambigüedad funcional;
- contradicción entre requisitos;
- riesgo de pérdida de datos;
- cambio de comportamiento visible;
- decisión que reduzca capacidades previamente acordadas;
- decisión irreversible o difícil de migrar con impacto de producto;
- necesidad de asumir información no proporcionada.

No se deben rellenar huecos de producto por inferencia silenciosa.

## 5. Regla ante incongruencias

Cuando una decisión nueva contradiga otra anterior:

1. no implementar ninguna de las dos interpretaciones por defecto;
2. señalar explícitamente la contradicción;
3. pedir una única decisión concreta al Product Owner;
4. actualizar el contrato funcional con la resolución.

La decisión más reciente solo sustituye a la anterior cuando la contradicción haya sido reconocida y resuelta explícitamente.

## 6. Issues

Toda implementación relevante debe poder rastrearse a una Issue.

Una Issue debe contener suficiente contexto para ejecutarse sin depender de una conversación privada, incluyendo cuando aplique:

- objetivo;
- contexto;
- comportamiento esperado;
- criterios de aceptación;
- restricciones;
- dependencias;
- casos límite;
- referencias al contrato funcional o ADR correspondiente.

Las Issues no deben introducir requisitos nuevos de producto sin aprobación.

### 6.1 Circuito FUNCIONAL ↔ TECH LEAD

Las dudas funcionales del MVP v1 se gestionan mediante Issues y labels de rol.

- `FUNCIONAL`: la issue está pendiente de respuesta o aclaración funcional.
- `TECH LEAD`: la issue está pendiente de análisis del Tech Lead.

Cuando el usuario indique al Tech Lead **“Revisa GitHub”** o una instrucción equivalente, el Tech Lead debe:

1. buscar las Issues abiertas del repositorio con la label `TECH LEAD`;
2. leer la issue completa y sus comentarios recientes;
3. contrastar las respuestas con `docs/PRODUCT_CONTRACT_V1.md`, ADRs y decisiones vigentes;
4. actualizar el contrato y la documentación cuando la respuesta cierre una decisión;
5. detectar contradicciones, huecos o nuevas preguntas relevantes;
6. si necesita nueva intervención funcional, comentar la issue con las cuestiones pendientes y devolverla a `FUNCIONAL`;
7. si no quedan dudas funcionales, contradicciones, dependencias pendientes ni entregables abiertos, consolidar la documentación y **cerrar la Issue sin requerir una autorización adicional del Product Owner**.

El Tech Lead es quien determina, tras su revisión, si una Issue del circuito está realmente resuelta y lista para cierre.

No es necesario que el usuario copie las respuestas del funcional al chat: GitHub es la fuente de verdad del circuito.

## 7. Arquitectura y ADR

Las decisiones arquitectónicas con impacto duradero se documentarán como Architecture Decision Records (ADR), por ejemplo:

- stack Android;
- persistencia local;
- modelo de snapshots históricos;
- formato de backup versionado;
- esquema del informe JSON;
- estrategia de imágenes;
- estrategia de migraciones de base de datos.

Un ADR debe explicar:

- contexto;
- decisión;
- alternativas consideradas cuando sea relevante;
- consecuencias;
- fecha/estado.

## 8. Restricciones tecnológicas

- El stack y las dependencias deben ser software libre y gratuito.
- La v1 no debe necesitar servicios de pago.
- La funcionalidad principal debe funcionar offline.
- Android 15 es la versión mínima soportada.
- Android 16 es el principal objetivo de prueba inicial.
- Distribución inicial mediante APK, no Google Play.

## 9. Protección del histórico

El histórico es información de dominio y debe protegerse especialmente.

Reglas generales:

- cambios de datos maestros no reescriben automáticamente sesiones antiguas;
- bajas de maestros con histórico son lógicas cuando corresponda;
- importaciones completas validan antes de sobrescribir;
- limpiezas históricas requieren confirmación fuerte;
- las migraciones futuras deben preservar significado y trazabilidad.

## 10. Forma de trabajo actual

Durante la fase de definición:

- se formula una sola pregunta de producto cada vez;
- la respuesta se incorpora al contrato funcional;
- no se inicia implementación que dependa de requisitos todavía ambiguos.

Cuando el contrato v1 esté suficientemente cerrado:

1. Tech Lead define arquitectura y stack.
2. Se documentan ADRs iniciales.
3. Se crea backlog de Issues priorizado.
4. Se prepara esqueleto del proyecto y CI.
5. El desarrollo avanza por Issues revisables y trazables.

## 11. Principio de simplicidad

GymUp v1 debe optimizar el flujo principal: planificar, ejecutar, registrar, consultar histórico y exportar.

No se incorporarán funcionalidades por anticipación si no aportan valor claro a la v1. Las extensiones futuras deben dejarse posibles cuando sea razonable, pero sin complicar innecesariamente el producto actual.
