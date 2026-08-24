# SessionStore

## Propósito

`SessionStore` es la capa de persistencia encargada de gestionar el historial de conversación en **Amazon DynamoDB**. Permite que el asistente RAG tenga memoria conversacional y entienda preguntas de seguimiento dentro de la misma sesión de chat (por ejemplo: *"¿Y cómo se compara con RNN?"* tras haber consultado sobre Transformers).

Esta clase aísla completamente la lógica de DynamoDB del resto del pipeline RAG.

## Esquema en Amazon DynamoDB

El microservicio utiliza la tabla `rag-sessions` configurada en modo **On-Demand** (`PAY_PER_REQUEST`), ideal para cargas de trabajo variables sin costos fijos cuando la aplicación no está en uso.

### Estructura del Ítem

| Atributo | Tipo DynamoDB | Descripción |
|---|---|---|
| `session_id` | `S` (Partition Key) | Identificador aislado de la sesión (`user_id#client_session_id`) |
| `user_id` | `S` | Identificador `sub` del usuario autenticado en Cognito |
| `messages` | `L` (List de Maps) | Historial cronológico de mensajes de la sesión |
| `created_at` | `S` (ISO-8601 UTC) | Marca temporal de creación de la sesión |
| `updated_at` | `S` (ISO-8601 UTC) | Marca temporal de la última interacción |

### Estructura de cada elemento en `messages` (`M` - Map):

```json
{
  "role": {"S": "user"},
  "content": {"S": "¿Qué es la atención?"},
  "ts": {"S": "2026-08-23T23:50:00.000Z"}
}
```

## Política de Recorte de Historial (`trimHistory`)

Para evitar que una sesión prolongada desborde la ventana de contexto del LLM o incremente exponencialmente los costos de procesamiento de tokens, `SessionStore` aplica una política FIFO (First In, First Out) acotada:

- Por defecto, conserva los últimos **6 turnos** (12 mensajes: 6 preguntas y 6 respuestas).
- El valor es configurable mediante la variable de entorno `HISTORY_TURNS`.
- El recorte preserva siempre la coherencia del diálogo descartando los turnos más antiguos en pares completos.

## Paso a paso

### 1. Se crea el store

- Constructor por defecto para Lambda: `fromEnvironment()` lee `SESSIONS_TABLE` y opcionalmente `HISTORY_TURNS`.
- Constructor con inyección de dependencias para testing local o unitario.

### 2. Carga de sesión (`loadSession`)

```java
List<ChatMessage> history = sessionStore.loadSession(sessionId);
```

1. Ejecuta `GetItem` en DynamoDB con la clave primaria `session_id`.
2. Si el registro no existe o la lista `messages` está ausente, retorna una lista vacía `List.of()`.
3. Convierte los atributos `AttributeValue` a objetos `ChatMessage`.
4. Aplica `trimHistory` para retornar como máximo `HISTORY_TURNS` turnos.

### 3. Registro de nuevo turno (`appendTurn`)

```java
sessionStore.appendTurn(sessionId, userMessage, assistantMessage);
```

1. Recupera el ítem existente (o inicia una nueva lista si la sesión es nueva).
2. Guarda el `user_id` del token Cognito junto con la sesión.
3. Añade el mensaje del usuario y la respuesta del asistente.
4. Aplica `trimHistory` para mantener el tamaño bajo control.
5. Actualiza `updated_at` y preserva la fecha original `created_at`.
6. Ejecuta `PutItem` en DynamoDB para guardar el estado actualizado.

## Conversión de Datos (Serialización)

- `toMessagesAttribute(List<ChatMessage>)`: convierte objetos Java en la estructura `AttributeValue` de tipo lista de mapas compatible con DynamoDB.
- `fromMessagesAttribute(List<AttributeValue>)`: deserializa de forma segura, ignorando elementos malformados o con atributos faltantes sin romper el flujo.

## Configuración y Variables de Entorno

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `SESSIONS_TABLE` | *(Obligatorio)* | Nombre de la tabla de sesiones en DynamoDB |
| `HISTORY_TURNS` | `6` | Cantidad máxima de turnos conversacionales a retener |

## Contexto para IA

Cuando una IA analice o modifique este componente debe considerar:

- **Idempotencia y atomicidad**: Cada actualización escribe el historial completo recortado. Para casos de altísima concurrencia sobre la misma sesión, se podría evaluar expresiones condicionales o `UpdateItem` con operaciones de lista, aunque para chat secuencial el esquema actual es óptimo y sencillo.
- **TTL (Time to Live)**: En entornos de producción a gran escala, se recomienda configurar un atributo `ttl` numérico para que DynamoDB expire y limpie sesiones inactivas automáticamente sin costo adicional.
- **Mapeo Seguro**: Nunca asumir que los ítems en DynamoDB contienen campos intactos; mantener validaciones de presencia para evitar `NullPointerException`.
