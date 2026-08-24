# ProyectoIA — RAG Serverless en AWS (Monorepo)

Sistema de Generación Aumentada por Recuperación (RAG) serverless en AWS, dividido en dos microservicios independientes:

```
ProyectoIA/
├── ingesta/    ← Ingestión e indexación de documentos (IMPLEMENTADO)
└── consulta/   ← Consulta RAG con LLM y Cognito (IMPLEMENTADO)
```

## Microservicios

| Microservicio | Estado | Stack | Descripción |
|---|---|---|---|
| `ingesta/` | Implementado y verificado | S3 → Lambda (Java 17) → Pinecone | Convierte PDFs en vectores semánticos (chunks con embedding integrado de Pinecone) |
| `consulta/` | Implementado y verificado | Lambda Function URL + Cognito (Java 17) → Pinecone + OpenRouter + DynamoDB | Consultas RAG autenticadas: búsqueda vectorial, contexto a LLM, sesiones aisladas por usuario |

## Comandos

Cada microservicio es un proyecto SAM/Maven independiente: todos los comandos se ejecutan desde su propia carpeta.

```bash
cd ingesta
mvn test                      # tests unitarios + integración
sam build && sam deploy ...   # despliegue del stack de ingesta
```

## Documentación Completa del Monorepo

### Microservicio de Ingesta (`ingesta/`)

| Documento | Descripción |
|---|---|
| [`ingesta/README.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/ingesta/README.md) | Guía principal del microservicio de ingesta |
| [`ingesta/PdfTextExtractor.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/ingesta/PdfTextExtractor.md) | Orquestación Lambda, eventos S3 y extracción PDFBox |
| [`ingesta/PineconeClient.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/ingesta/PineconeClient.md) | Persistencia de chunks y embeddings integrados en Pinecone |
| [`ingesta/TextChunker.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/ingesta/TextChunker.md) | Limpieza de texto, normalización y algoritmo de chunking |
| [`ingesta/sam_explicacion.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/ingesta/sam_explicacion.md) | Detalle de infraestructura SAM (`template.yaml` y `samconfig.toml`) |
| [`ingesta/ContextIA.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/ingesta/ContextIA.md) | Contexto de arquitectura general y flujos RAG |
| [`ingesta/ContextoIAWS.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/ingesta/ContextoIAWS.md) | Inventario de recursos de AWS del stack de ingesta |

### Microservicio de Consulta (`consulta/`)

| Documento | Descripción |
|---|---|
| [`consulta/README.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/README.md) | Guía principal del microservicio de consulta RAG |
| [`consulta/QueryHandler.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/QueryHandler.md) | Handler de Function URL, ciclo de vida de consulta y respuestas |
| [`consulta/CognitoJwtVerifier.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/CognitoJwtVerifier.md) | Autenticación Cognito, ID Tokens, JWKS y sesiones aisladas |
| [`consulta/PineconeSearchClient.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/PineconeSearchClient.md) | Búsqueda vectorial semántica sobre chunks indexados |
| [`consulta/DeepSeekClient.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/DeepSeekClient.md) | Invocación de LLMs en OpenRouter (modelos free, reintentos y 429) |
| [`consulta/RagContextBuilder.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/RagContextBuilder.md) | Construcción de prompt RAG con citas explícitas `[Fuente N]` |
| [`consulta/SessionStore.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/SessionStore.md) | Persistencia de historial conversacional y recorte FIFO en DynamoDB |
| [`consulta/sam_explicacion.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/sam_explicacion.md) | Detalle de infraestructura SAM de consulta (Function URL, DynamoDB) |
| [`consulta/ContextIA.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/ContextIA.md) | Contexto de arquitectura del flujo de consulta y directivas para IA |
| [`consulta/ContextoIAWS.md`](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/ContextoIAWS.md) | Inventario de recursos de AWS del stack de consulta |
