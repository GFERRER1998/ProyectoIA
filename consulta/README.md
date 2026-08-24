# Microservicio de Consulta (RAG) — IMPLEMENTADO

Pipeline serverless de consulta RAG: recibe preguntas, recupera los chunks más relevantes de Pinecone, genera la respuesta con un LLM vía OpenRouter y guarda las sesiones en DynamoDB.

```
Usuario ──POST──> Lambda Function URL (gratis) ──> QueryHandler (Java 17)
                        │
                        ├─ PineconeSearchClient: búsqueda vectorial (top_k chunks + score)
                        ├─ RagContextBuilder: prompt RAG (sistema + contexto + historial + pregunta)
                        ├─ DeepSeekClient: chat completions vía OpenRouter (modelo :free)
                        └─ SessionStore: persistencia del turno en DynamoDB
```

## Arquitectura actual

| Componente | Rol |
|---|---|
| **Lambda Function URL** | Punto de entrada HTTPS público, costo $0 por request (payload v2, `APIGatewayV2HTTPEvent`) |
| **Lambda `QueryFunction`** (Java 17, 1024 MB, timeout 60s) | Orquesta recuperación + generación + sesión |
| **Pinecone** `rag-index` / namespace `documents-dev` | Búsqueda vectorial sobre los chunks indexados por `ingesta/` (search por texto, campo `text`) |
| **OpenRouter** (modelo `:free`) | LLM para generar respuestas RAG con citas de fuente |
| **DynamoDB** `rag-sessions` (on-demand) | Una fila por sesión: `session_id` (PK), `messages`, `created_at`, `updated_at` |
| **Secrets Manager** `pinecone/pdf-extractor` y `openrouter/chat` | API keys leídas por IAM, nunca en código ni logs |

## Modelo LLM (configurable)

- Default: `nvidia/nemotron-3-ultra-550b-a55b:free` (1M contexto, el más potente del roster free actual).
- Los modelos free de DeepSeek (`deepseek/*:free`) **ya no están disponibles** en OpenRouter (el roster rota). El código es agnóstico al modelo: para volver a DeepSeek cuando reaparezca, solo cambia la variable `OPENROUTER_MODEL` (env var o parámetro del template) a `deepseek/<modelo>:free`.
- Límites free: ~20 req/min y 50 req/día (1000/día si se compran $10 de crédito una sola vez).

## Endpoint de consulta

```bash
curl -X POST "https://<FUNCTION_URL>/" \
  -H "Content-Type: application/json" \
  -d '{"question":"que es la atencion?","session_id":"opcional"}'
```

Respuesta:

```json
{
  "answer": "Respuesta con citas [documents/x.pdf (chunk 3)]",
  "sources": [{"sourceKey": "documents/x.pdf", "chunkIndex": "3", "score": 0.88}],
  "session_id": "uuid-de-la-sesion",
  "model": "nvidia/nemotron-3-ultra-550b-a55b:free"
}
```

- Sin `session_id` se crea una sesión nueva; reenviando el mismo `session_id` el LLM recibe el historial (últimos 6 turnos por defecto).
- Errores: `400` (body inválido / falta `question`), `502` (Pinecone/OpenRouter/DynamoDB).

## Configuración de la Lambda (variables de entorno)

| Variable | Origen | Descripción |
|---|---|---|
| `PINECONE_SECRET_ARN` / `PINECONE_INDEX_HOST` / `PINECONE_NAMESPACE` / `PINECONE_TEXT_FIELD` | Parámetros CloudFormation | Cliente Pinecone (mismos valores que `ingesta/`) |
| `OPENROUTER_SECRET_ARN` | Parámetro CloudFormation | ARN del secreto `openrouter/chat` |
| `OPENROUTER_MODEL` | Parámetro CloudFormation (default `nvidia/nemotron-3-ultra-550b-a55b:free`) | Modelo LLM |
| `SESSIONS_TABLE` | CloudFormation | Nombre de la tabla DynamoDB |
| `PINECONE_TOP_K` / `HISTORY_TURNS` | Opcionales (defaults 5 / 6) | Chunks por consulta y turnos de historial |

## Secretos

### Local (desarrollo)
- Copiar `.env.example` a `.env` y completar `PINECONE_API_KEY`, `PINECONE_INDEX_HOST`, `OPENROUTER_API_KEY`.
- `.env` está en `.gitignore` — **nunca** commitearlo.

### AWS (producción)
- `pinecone/pdf-extractor` (compartido con `ingesta/`): key de Pinecone.
- `openrouter/chat`: `{"apiKey":"sk-or-..."}` — creado con:
  ```bash
  aws secretsmanager create-secret --name openrouter/chat \
    --secret-string '{"apiKey":"sk-or-..."}' --region us-east-1
  ```

## Despliegue

```bash
sam build
sam deploy --region us-east-1 \
  --parameter-overrides \
    "PineconeSecretArn=arn:aws:secretsmanager:us-east-1:<cuenta>:secret:pinecone/pdf-extractor-<sufijo> \
     PineconeIndexHost=https://rag-index-xxxx.svc.<region>.pinecone.io \
     PineconeNamespace=documents-dev \
     PineconeTextField=text \
     OpenRouterSecretArn=arn:aws:secretsmanager:us-east-1:<cuenta>:secret:openrouter/chat-<sufijo> \
     OpenRouterModel=nvidia/nemotron-3-ultra-550b-a55b:free"
```

Salidas del stack: URL de la función y nombre de la tabla de sesiones.

## Verificación

- **Tests:** `set -a; source .env; set +a; mvn test` (unitarios + integración contra Pinecone y OpenRouter reales; se omiten si faltan variables).
- **E2E:** el curl del endpoint anterior + verificar el item en DynamoDB:
  ```bash
  aws dynamodb get-item --table-name <SessionsTableName> \
    --key '{"session_id":{"S":"<id>"}}' --region us-east-1
  ```

## Notas

- Si el modelo free responde `429`, espera para la siguiente consulta (límites del tier gratuito).
- CORS configurable por parámetro `CorsAllowedOrigins` (default `*`); `AuthType: NONE` es apto para desarrollo — en producción considera `AWS_IAM`, API Gateway o WAF.