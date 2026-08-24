# Infraestructura AWS (Microservicio de Consulta)

## Recursos Desplegados y Gestionados

La infraestructura del microservicio de consulta es gestionada mediante el stack CloudFormation `consulta-rag-lambda-stack` (región `us-east-1`).

| Recurso | Tipo | Identificador / Nombre | Rol en el Sistema |
|---|---|---|---|
| **Lambda Function** | `AWS::Serverless::Function` | `consulta-rag-lambda-stack-QueryFunction-*` | Orquesta el flujo RAG de consulta (Java 17, 1024 MB RAM, 60s timeout) |
| **Function URL** | `AWS::Lambda::Url` | `https://<url-id>.lambda-url.us-east-1.on.aws/` | Endpoint HTTPS público ($0 costo por invocación, CORS habilitado) |
| **Tabla DynamoDB** | `AWS::DynamoDB::Table` | `rag-sessions` (o nombre asignado por stack) | Almacena turnos de conversación indexados por `session_id` (On-Demand) |
| **Stack CloudFormation** | `AWS::CloudFormation::Stack` | `consulta-rag-lambda-stack` | Pila que agrupa y aprovisiona los recursos SAM |
| **Secreto Pinecone** | `AWS::SecretsManager::Secret` | `pinecone/pdf-extractor` | Contiene la API Key de Pinecone (compartido con `ingesta/`) |
| **Secreto OpenRouter** | `AWS::SecretsManager::Secret` | `openrouter/chat` | Contiene la API Key de OpenRouter (`{"apiKey":"sk-or-..."}`) |
| **Índice Pinecone (Externo)** | Vector DB Serverless | `rag-index` (región `us-east-1`) | Base de datos vectorial con embedding integrado (dim: 1024, métrica: cosine) |

---

## Interacción entre Microservicios

```text
[ Microservicio Ingesta (ingesta/) ]
  └── Sube PDFs a S3 -> Chunking -> Upsert a Pinecone (rag-index, ns: documents-dev)
                                           │
                                           ▼
[ Microservicio Consulta (consulta/) ]
  └── Recibe preguntas vía Function URL -> Búsqueda en Pinecone -> OpenRouter LLM -> DynamoDB
```
