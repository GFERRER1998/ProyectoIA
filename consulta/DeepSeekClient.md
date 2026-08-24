# DeepSeekClient

## Propósito

`DeepSeekClient` es la capa que conecta el microservicio de consulta con el Modelo de Lenguaje Grande (LLM) a través de la API de OpenRouter. Recibe los mensajes ya formateados por `RagContextBuilder` (directivas del sistema con citas de fuentes, historial de la sesión y la pregunta actual del usuario) y devuelve la respuesta textual generada.

Esta clase no realiza búsquedas vectoriales en Pinecone ni gestiona tablas de DynamoDB. Su entrada esperada es una lista cronológica de objetos `ChatMessage`.

## Importante: endpoint y protocolo utilizado

OpenRouter proporciona una interfaz unificada y compatible con la especificación de OpenAI:

```text
POST https://openrouter.ai/api/v1/chat/completions
```

El cuerpo de la petición utiliza el formato JSON estándar:

```json
{
  "model": "deepseek/deepseek-chat-v3-0324:free",
  "messages": [
    {"role": "system", "content": "Eres un asistente RAG..."},
    {"role": "user", "content": "¿Qué es la atención?"}
  ],
  "temperature": 0.2
}
```

- **Temperatura baja (`0.2`)**: garantiza respuestas deterministas, rigurosas y estrictamente apegadas al contexto documental provisto, reduciendo alucinaciones.
- **Header `X-Title`**: identifica a la aplicación en los paneles de analítica y métricas de OpenRouter.

## Paso a paso

### 1. Se crea el cliente

El constructor recibe:
- `apiKey`: token de autenticación para OpenRouter (`Bearer sk-or-...`).
- `model`: identificador del modelo (por defecto `deepseek/deepseek-chat-v3-0324:free` o el configurado por variable de entorno).
- `httpClient`: instancia reutilizable de `HttpClient` configurada con timeout de conexión (5 segundos).
- `requestTimeout`: tiempo límite total de respuesta (60 segundos).

### 2. Se cargan secretos en Lambda

El método de fábrica `fromEnvironment` obtiene:
- `OPENROUTER_SECRET_ARN`: ARN del secreto en AWS Secrets Manager.
- La API key desde Secrets Manager mediante llamadas seguras de IAM (permiso `secretsmanager:GetSecretValue`).
- `OPENROUTER_MODEL`: modelo a invocar (permite alternar entre DeepSeek, Nemotron, Llama u otros sin recompilar el JAR).

El secreto en Secrets Manager puede contener directamente el string o un JSON:

```json
{"apiKey": "sk-or-v1-..."}
```

La clave nunca se expone en código ni en los logs de CloudWatch.

### 3. Se valida la entrada

El método `chat(List<ChatMessage> messages)` comprueba que la lista no sea nula ni vacía antes de emitir la llamada HTTP.

### 4. Se construye la petición HTTP

Se serializa la lista de mensajes en un array JSON preservando estrictamente el orden:
1. `system`: prompt de sistema con fragmentos documentales citados.
2. `user` / `assistant`: turnos históricos previos.
3. `user`: pregunta actual del usuario.

El cuerpo nunca se escribe en los registros para preservar la privacidad de los usuarios y de la información sensible.

### 5. Se ejecuta la llamada y se procesa la respuesta

- **Respuestas exitosas (`2xx`)**: se parsea el JSON extrayendo `choices[0].message.content`.
- **Límites de tasa (`HTTP 429`)**: frecuente en tiers gratuitos (~20 req/min o ~50 req/día). El cliente captura este escenario y arroja una excepción descriptiva para que el consumidor entienda la causa.
- **Errores del servidor (`HTTP 5xx`)**: se marcan como transitorios para aplicar reintentos automáticos.
- **Errores de cliente (`HTTP 4xx` no 429)**: se propagan inmediatamente sin reintentos innecesarios.

### 6. Se aplican reintentos con backoff

Se realizan hasta 3 intentos con pausas exponenciales crecientes:

```text
Intento 1 -> espera 250 ms
Intento 2 -> espera 500 ms
Intento 3 -> falla y propaga la excepción
```

Si el hilo de ejecución es interrumpido, se restablece el estado de interrupción y se cancela la operación limpiamente.

## Relación en el flujo RAG

```text
QueryHandler
  -> recupera chunks con PineconeSearchClient
  -> RagContextBuilder ensambla el prompt con citas [Fuente N]
  -> DeepSeekClient envía la conversación a OpenRouter
  -> OpenRouter devuelve la respuesta fundamentada
  -> QueryHandler guarda en SessionStore y responde al usuario
```

## Parámetros y Configuración

| Parámetro / Variable | Valor por defecto | Descripción |
|---|---|---|
| `OPENROUTER_SECRET_ARN` | *(Obligatorio)* | ARN del secreto con la API Key |
| `OPENROUTER_MODEL` | `deepseek/deepseek-chat-v3-0324:free` | Modelo LLM en OpenRouter |
| `MAX_ATTEMPTS` | 3 | Intentos máximos ante fallos temporales |
| `TEMPERATURE` | 0.2 | Grado de aleatoriedad del modelo |

## Contexto para IA

Cuando una IA analice o modifique este componente debe considerar:

- **Seguridad**: bajo ninguna circunstancia escribir API keys o volcar payloads JSON con conversaciones en logs.
- **Modelo Agnóstico**: la clase no debe depender de características propietarias de un modelo particular; debe mantener total compatibilidad con el estándar `/chat/completions`.
- **Temperatura**: no elevar la temperatura para tareas de consulta RAG donde la exactitud fáctica y las citas son críticas.
- **Manejo de Errores**: preservar la detección y reporte explícito de HTTP 429 para alertar sobre límites de cuota en tiers gratuitos.
