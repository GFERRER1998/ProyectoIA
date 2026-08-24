# QueryHandler

## Propósito

`QueryHandler` es el punto de entrada principal (Handler) de AWS Lambda para el microservicio de consulta RAG. Se expone públicamente a través de una **Lambda Function URL** (costo $0 por request de infraestructura) implementando la interfaz `RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>`.

Coordina el ciclo de vida completo de una consulta:

```text
HTTP POST (JSON) -> Validar -> Historial DynamoDB -> Búsqueda Pinecone -> Prompt RAG -> Invocación LLM -> Guardar Sesión -> HTTP 200 (JSON)
```

## Arquitectura y Responsabilidades

`QueryHandler` actúa como orquestador de alto nivel delegando responsabilidades especializadas:

| Componente | Responsabilidad |
|---|---|
| `QueryHandler` | Parseo HTTP v2, orquestación, manejo de errores y serialización de respuesta |
| `PineconeSearchClient` | Búsqueda semántica en base vectorial con embedding integrado |
| `RagContextBuilder` | Formateo del System Prompt con citas explícitas `[Fuente N]` y ensamblado de mensajes |
| `DeepSeekClient` | Envío de la conversación a OpenRouter y obtención de respuesta generada |
| `SessionStore` | Recuperación y almacenamiento de turnos de conversación en Amazon DynamoDB |

## Paso a paso

### 1. Lambda recibe el evento HTTP

El método `handleRequest` recibe un `APIGatewayV2HTTPEvent` generado por la Function URL.

El cuerpo de la petición se valida mediante `parseRequest`:

```json
{
  "question": "¿Qué es la arquitectura Transformer?",
  "session_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
}
```

- Si falta el campo `question` o está vacío, retorna inmediatamente `HTTP 400 Bad Request`.
- Si el cliente no proporciona `session_id`, la Lambda genera automáticamente un nuevo identificador UUID v4 para iniciar una sesión independiente.

### 2. Se recupera el historial de la sesión

Consulta `SessionStore.loadSession(sessionId)`. Si la sesión ya existe, devuelve los turnos previos (recortados a los últimos N turnos configurados, por defecto 6). Si es nueva, devuelve una lista vacía.

### 3. Se ejecuta la búsqueda vectorial en Pinecone

Invoca `searchClient.search(question)`. Pinecone vectoriza la pregunta y devuelve la lista de los `top_k` chunks más relevantes con sus metadatos de origen (documento, chunk index y score).

### 4. Se construye el prompt RAG

`RagContextBuilder.buildMessages(question, hits, history)` construye los mensajes:
1. `system`: instrucciones estrictas de veracidad y el bloque de fragmentos documentales numerados `[Fuente N]`.
2. Historial de mensajes previos (si existen).
3. `user`: la pregunta actual del usuario.

### 5. Se genera la respuesta con el LLM

`llmClient.chat(messages)` transmite la conversación a OpenRouter (modelo configurado en `OPENROUTER_MODEL`). El LLM responde citando las fuentes entre corchetes, por ejemplo: `[documents/attention.pdf (chunk 3)]`.

### 6. Se persiste el turno en DynamoDB

`sessionStore.appendTurn(sessionId, userMessage, assistantMessage)` registra el par pregunta-respuesta en la tabla `rag-sessions` con marcas de tiempo `created_at` y `updated_at`.

### 7. Se retorna la respuesta estructurada

Devuelve un `APIGatewayV2HTTPResponse` con código `200 OK`, `Content-Type: application/json` y el siguiente cuerpo:

```json
{
  "answer": "La arquitectura Transformer se basa en mecanismos de atención... [documents/attention.pdf (chunk 3)]",
  "sources": [
    {
      "sourceKey": "documents/attention.pdf",
      "chunkIndex": "3",
      "score": 0.892
    }
  ],
  "session_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "model": "nvidia/nemotron-3-ultra-550b-a55b:free"
}
```

## Manejo de Errores y Códigos HTTP

| Código | Condición | Respuesta |
|---|---|---|
| `200 OK` | Consulta completada con éxito | JSON con `answer`, `sources`, `session_id`, `model` |
| `400 Bad Request` | Body JSON inválido o ausencia del campo obligatorio `question` | `{"error": "Falta el campo 'question' en el body JSON."}` |
| `502 Bad Gateway` | Falla no recuperable en Pinecone, OpenRouter o DynamoDB | `{"error": "Error interno procesando la consulta."}` (detalle completo en CloudWatch Logs) |

## Configuración y Variables de Entorno

La función se parametriza a través de CloudFormation / SAM (`template.yaml`):

- `PINECONE_SECRET_ARN`: ARN del secreto con la clave de Pinecone.
- `PINECONE_INDEX_HOST`: URL HTTPS del índice vectorial.
- `PINECONE_NAMESPACE`: Namespace de búsqueda.
- `PINECONE_TEXT_FIELD`: Campo mapeado en el índice.
- `OPENROUTER_SECRET_ARN`: ARN del secreto con la clave de OpenRouter.
- `OPENROUTER_MODEL`: Modelo LLM a utilizar.
- `SESSIONS_TABLE`: Nombre de la tabla DynamoDB para almacenar el historial.

## Contexto para IA

Cuando una IA analice o modifique este archivo debe considerar:

- **Orquestación Pura**: No mover lógica detallada de HTTP, parsing de respuestas de Pinecone o construcción de prompts dentro de `QueryHandler`; mantener la delegación a sus clases correspondientes.
- **Seguridad**: Los errores 502 nunca deben exponer trazas de excepciones internas, ARNs de secretos ni datos crudos de bases de datos al cliente HTTP; los detalles siempre van a `logger.error`.
- **Sesiones**: El soporte de memoria conversacional depende de la persistencia de `session_id`. Si se añade autenticación (Cognito), `session_id` podrá asociarse a un `user_id`.
