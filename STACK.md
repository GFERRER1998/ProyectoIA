# STACK.MD — Stack de Tecnologías y Servicios (ProyectoIA)

> Documento de referencia para generar diagramas de arquitectura.
> Sistema RAG (Retrieval-Augmented Generation) serverless en AWS con dos microservicios backend y un frontend SPA.

---

## 1. Visión General

| Aspecto | Valor |
|---|---|
| Tipo de sistema | RAG serverless (ingesta de PDFs + consulta conversacional) |
| Nube | AWS (región `us-east-1`) |
| Patrón | Serverless (Lambda + Function URL, sin API Gateway) |
| Autenticación | Amazon Cognito User Pool (JWT RS256 verificado en-process) |
| Base vectorial | Pinecone serverless (embedding integrado, sin proveedor externo de embeddings) |
| LLM | OpenRouter (modelos free; hoy `nvidia/nemotron-3-ultra-550b-a55b:free`) |
| Monorepo | `frontend/`, `ingesta/`, `consulta/` |

---

## 2. Frontend (`frontend/`)

| Tecnología | Versión | Rol |
|---|---|---|
| Next.js | ^16.3.2 | Framework React fullstack (App Router, páginas: login, register, confirm, chat, documents, history) |
| React | ^19.0.0 | Librería UI |
| aws-amplify | ^6.13.1 | SDK Cognito: registro, confirmación, login SRP, refresh token |
| TypeScript | ^5.8.2 | Tipado estático |
| ESLint | ^9.22.0 | Linting |

Comunicación: el frontend llama directamente a las **Function URL** de las Lambdas (HTTPS + CORS, header `Authorization: Bearer <ID_TOKEN>`) y sube PDFs a S3 mediante **URLs prefirmadas**.

---

## 3. Backend — Microservicio de Ingesta (`ingesta/`)

Lenguaje: **Java 17** · Build: **Maven** (Shade Plugin → Fat JAR) · IaC: **AWS SAM / CloudFormation** (`template.yaml`)

### Lambdas

| Lambda | Handler | Trigger | Memoria / Timeout | Función |
|---|---|---|---|---|
| `UploadUrlFunction` | `com.example.UploadUrlHandler` | Function URL (POST) | 512 MB / 15 s | Emite URLs prefirmadas S3 para subir PDFs; registra documento en DynamoDB |
| `PdfTextExtractorFunction` | `com.example.PdfTextExtractor` | Evento **S3 `ObjectCreated`** (prefijo `documents/`, sufijo `.pdf`) | 1024 MB / 120 s | Extrae texto (PDFBox), divide en chunks y hace upsert en Pinecone; actualiza estado en DynamoDB |
| `DocumentsFunction` | `com.example.DocumentHandler` | Function URL (GET, DELETE) | 512 MB / 15 s | Lista y elimina documentos del usuario |

### Librerías clave

| Librería | Versión | Uso |
|---|---|---|
| AWS SDK v2 (BOM) | 2.25.11 | S3, DynamoDB, Secrets Manager |
| aws-lambda-java-core / events | 1.2.3 / 3.11.4 | Runtime Lambda y eventos S3 |
| Apache PDFBox | 3.0.2 | Extracción de texto de PDFs |
| Gson | 2.11.0 | Construcción de registros NDJSON para Pinecone |
| nimbus-jose-jwt | 9.37.3 | Verificación de ID Tokens de Cognito (JWKS RS256) |
| JUnit 5 | 5.10.2 | Tests unitarios |

---

## 4. Backend — Microservicio de Consulta (`consulta/`)

Lenguaje: **Java 17** · Build: **Maven** · IaC: **AWS SAM / CloudFormation** (`template.yaml`)
Crea también la infraestructura compartida de autenticación (Cognito).

### Lambdas

| Lambda | Handler | Trigger | Memoria / Timeout | Función |
|---|---|---|---|---|
| `QueryFunction` | `com.example.QueryHandler` | Function URL (POST) | 1024 MB / 60 s | Pipeline RAG: valida JWT → busca en Pinecone → construye prompt con citas `[Fuente N]` → invoca LLM vía OpenRouter → guarda turno en DynamoDB |
| `SessionFunction` | `com.example.SessionHandler` | Function URL (GET, DELETE) | 512 MB / 15 s | Historial: lista sesiones por usuario (GSI), consulta una sesión, elimina sesión |

Componentes internos: `CognitoJwtVerifier` (JWKS cacheado), `PineconeSearchClient`, `RagContextBuilder`, `DeepSeekClient` (reintentos ante HTTP 429), `SessionStore` (recorte FIFO, sesiones aisladas `{sub}#{session_id}`).

---

## 5. Servicios AWS (infraestructura)

| Servicio AWS | Recurso | Configuración |
|---|---|---|
| **Lambda** | 5 funciones (3 ingesta + 2 consulta) | Java 17, x86_64, Function URL con `AuthType: NONE` + CORS |
| **S3** | Bucket de documentos | Prefijo `documents/*.pdf`; CORS para PUT/GET/HEAD desde el frontend |
| **DynamoDB** | `SessionsTable` | On-demand; PK `session_id`; GSI `user-updated-index` (`user_id` HASH, `updated_at` RANGE); SSE habilitado |
| **DynamoDB** | `DocumentsTable` | On-demand; PK `document_id`; GSI por usuario; estados PENDING → PROCESSING → READY / ERROR |
| **Cognito** | User Pool + App Client público | Login email, SRP + refresh token, ID Token válido 1 h, refresh 30 días, MFA opcional, sin client secret |
| **Secrets Manager** | 2 secretos | API key de Pinecone (`pinecone/pdf-extractor`) y de OpenRouter (`openrouter/chat`) — acceso solo por IAM |
| **IAM** | Roles por lambda | Mínimo privilegio (GetObject/PutObject sobre `documents/*`, DynamoDB tabla+GSI, GetSecretValue por ARN) |

> Nota: NO se usa API Gateway ni Identity Pool. La validación JWT se hace dentro de cada Lambda contra las JWKS del User Pool.

---

## 6. Servicios Externos (no-AWS)

| Servicio | Detalle | Consumido por |
|---|---|---|
| **Pinecone** (SaaS vectorial) | Índice serverless `rag-index` en `us-east-1`, métrica `cosine`, 1024 dims, **embedding integrado** (`llama-text-embed-v2`, `field_map` → campo `text`). Namespace: `documents-dev`. Los embeddings los genera Pinecone server-side al recibir NDJSON | `PdfTextExtractor` (upsert) · `QueryHandler` (query top-K) |
| **OpenRouter** (gateway LLM) | API OpenAI-compatible; modelo actual `nvidia/nemotron-3-ultra-550b-a55b:free` (parametrizable) | `DeepSeekClient` dentro de `QueryFunction` |

---

## 7. Flujos Principales (para aristas del diagrama)

### Flujo A — Autenticación
```
Usuario (navegador)
  └─> Frontend Next.js (aws-amplify)
        └─> Cognito User Pool: SignUp → Confirm → USER_SRP_AUTH → ID Token (JWT RS256)
```

### Flujo B — Ingesta de documentos
```
Frontend ──POST (Bearer ID Token)──> UploadUrlFunction ──> valida JWT (Cognito JWKS) ──> item en DocumentsTable (PENDING) ──> devuelve URL prefirmada
Frontend ──PUT PDF──> S3 Bucket (documents/*.pdf)
S3 ObjectCreated ──trigger──> PdfTextExtractorFunction
  ├─ lee PDF de S3 (PDFBox)
  ├─ TextChunker (limpieza + chunking)
  ├─ PineconeClient ──NDJSON upsert──> Pinecone rag-index (embedding server-side)
  └─ DocumentsTable (estado PROCESSING → READY / ERROR)
Frontend ──GET/DELETE──> DocumentsFunction ──> DocumentsTable (+ borrado en S3/Pinecone)
```

### Flujo C — Consulta RAG
```
Frontend ──POST {pregunta, session_id} (Bearer ID Token)──> QueryFunction (Function URL)
  ├─ CognitoJwtVerifier (valida firma/exp/iss/aud → sub)
  ├─ SessionStore ──> DynamoDB SessionsTable (historial FIFO)
  ├─ PineconeSearchClient ──query vectorial top-K──> Pinecone rag-index
  ├─ RagContextBuilder (prompt sistema + contexto citado [Fuente N] + historial)
  ├─ DeepSeekClient ──HTTPS──> OpenRouter (LLM :free) ──> respuesta generada
  └─ persiste turno Q/R en DynamoDB ──> responde JSON al frontend
Frontend ──GET/DELETE──> SessionFunction ──> DynamoDB SessionsTable (historial)
```

---

## 8. Resumen de Conexiones (lista plana para diagramas)

1. Usuario → Frontend Next.js (HTTPS navegador)
2. Frontend → Cognito User Pool (auth: signup/login/refresh, Amplify)
3. Frontend → UploadUrlFunction (POST, JWT)
4. Frontend → S3 Bucket (PUT prefirmado)
5. Frontend → DocumentsFunction (GET/DELETE, JWT)
6. Frontend → QueryFunction (POST, JWT)
7. Frontend → SessionFunction (GET/DELETE, JWT)
8. S3 → PdfTextExtractorFunction (evento ObjectCreated .pdf)
9. UploadUrlFunction → S3 (presign) y → DocumentsTable (PutItem)
10. UploadUrlFunction / DocumentsFunction / QueryFunction / SessionFunction → Cognito JWKS (verificación JWT HTTPS)
11. PdfTextExtractorFunction → S3 (GetObject)
12. PdfTextExtractorFunction → DocumentsTable (UpdateItem)
13. PdfTextExtractorFunction  li→ Secrets Manager (API key Pinecone)
14. PdfTextExtractorFunction → Pinecone (upsert NDJSON)
15. DocumentsFunction → S3 (DeleteObject) y → DocumentsTable (GetItem/Query/DeleteItem)
16. QueryFunction → Secrets Manager (keys Pinecone + OpenRouter)
17. QueryFunction → Pinecone (query vectorial)
18. QueryFunction → OpenRouter (chat completion LLM)
19. QueryFunction → SessionsTable (GetItem/PutItem/UpdateItem)
20. SessionFunction → SessionsTable (GetItem/Query/DeleteItem + GSI)

---

*Generado el 2026-08-24 a partir de: `README.md`, `BACKDEIA.MD`, `pancognito.md`, `ingesta/template.yaml`, `consulta/template.yaml`, `frontend/package.json`, poms Maven.*
