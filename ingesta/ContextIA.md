# Contexto de Arquitectura: RAG en AWS (Retrieval-Augmented Generation)

## Resumen General
Esta arquitectura describe un sistema de Generación Aumentada por Recuperación (RAG) serverless implementado en AWS. El sistema consta de dos flujos principales: el **Flujo de Ingestión e Indexación de Documentos** (implementado) y el **Flujo de Consulta / Respuesta (RAG)** (futuro), apoyados por servicios auxiliares de la nube.

> Estado: **ingestión completa y verificada (v1)**. **Consulta RAG implementada y verificada (v2, ver `consulta/`)**. Cognito y API Gateway son fases futuras.

---

## Servicios de AWS y sus Roles en la Arquitectura

### 1. Entrada y Salida (Interfaces)
* **Lambda Function URL (HTTPS, implementado en `consulta/`):** punto de entrada público del flujo de consulta (costo $0 por request; API Gateway queda como opción futura si se necesita WAF/throttling).

### 2. Cómputo y Lógica Principal
* **AWS Lambda `PdfTextExtractorFunction` (Java 17, implementado):**
  * En la **ingestión**: se activa por el evento `s3:ObjectCreated:*` del bucket (prefijo `documents/`, sufijo `.pdf`). Descarga el PDF, extrae texto (PDFBox), divide en chunks (`TextChunker`) y envía los registros a Pinecone (`PineconeClient`).
* **AWS Lambda `QueryFunction` (Java 17, implementado en `consulta/`):**
  * En las **consultas**: recibe la pregunta, busca en Pinecone (`PineconeSearchClient`), arma el prompt RAG (`RagContextBuilder`), llama al LLM vía OpenRouter (`DeepSeekClient`) y guarda el turno en DynamoDB (`SessionStore`).

### 3. Almacenamiento de Archivos y Documentos
* **Amazon S3 — Bucket `rag-documents-683023468765` (implementado):**
  * `documents/`: **Aquí se suben los PDFs** (`.pdf`). El evento `s3:ObjectCreated` (prefijo `documents/`, sufijo `.pdf`) dispara la Lambda automáticamente.
  * `processed/`, `embeddings/`, `backups/`: *(reservados)* para fases futuras.

### 4. Base de Datos Vectorial (RAG / Knowledge Base)
* **Pinecone — índice `rag-index` (implementado):** serverless, región `us-east-1`, métrica `cosine`, 1024 dimensiones, **embedding integrado** (`field_map`: campo `text`). Almacena los registros y genera los vectores internamente al recibir el NDJSON del upsert. La API key vive en **AWS Secrets Manager** (`pinecone/pdf-extractor`).

### 5. Inteligencia Artificial / Modelos Generativos (LLM & Embeddings)
* **Embeddings (implementado):** los genera **Pinecone server-side** (modelo del índice, `llama-text-embed-v2` o similar). La Lambda **no** llama a ningún proveedor de embeddings.
* **LLM de consulta (implementado en `consulta/`):** DeepSeek u otros modelos `:free` vía **OpenRouter** (hoy: `nvidia/nemotron-3-ultra-550b-a55b:free`). Key en Secrets Manager (`openrouter/chat`).

### 6. Persistencia de Sesión e Historial
* **Amazon DynamoDB (implementado en `consulta/`):** tabla `rag-sessions` (PK `session_id`) con el historial de conversaciones.

---

## Servicios de Apoyo y Seguridad

* **Amazon Cognito:** *(futuro)* gestión de identidad y autenticación de usuarios.
* **Amazon CloudWatch (implementado):** logs de la Lambda (grupo `/aws/lambda/pdf-extractor-lambda-stac-PdfTextExtractorFunction-*`).
* **AWS Secrets Manager (implementado):** secreto `pinecone/pdf-extractor` con la API key (`{"apiKey":"..."}`); la Lambda solo tiene `secretsmanager:GetSecretValue` sobre ese ARN.
* **Amazon EventBridge:** *(futuro)* programación de tareas periódicas, eventos asíncronos y reindexación.

---

## Flujos de Trabajo Paso a Paso

### A. Flujo de Ingestión e Indexación de Documentos (implementado y verificado)
1. **Carga:** Se sube un archivo `.pdf` a `s3://rag-documents-683023468765/documents/`.
2. **Evento:** S3 dispara `s3:ObjectCreated:*` (filtros: prefijo `documents/`, sufijo `.pdf`) e invoca a **AWS Lambda**.
3. **Procesamiento:** Lambda extrae el texto con **PDFBox** y lo divide en fragmentos (*chunks* ~600 tokens, overlap 60) con **TextChunker**.
4. **Persistencia:** `PineconeClient` envía NDJSON a `POST /records/namespaces/documents-dev/upsert` (campo `text` + metadata: `document_id`, `source_bucket`, `source_key`, `chunk_index`, `content_type`, `ingested_at`).
5. **Vectorización (server-side):** **Pinecone** genera los embeddings del texto al indexar (asíncrono; buscable en segundos).
6. **IDs determinísticos:** `sha256(bucket+key+etag)[0:16]_chunk_N` → re-subir el mismo archivo sobrescribe, no duplica.

### B. Flujo de Consulta (RAG) — implementado (v2)
1. **Envío:** El **Usuario** realiza una pregunta (HTTP POST) a la **Lambda Function URL** del microservicio `consulta/`.
2. **Recepción:** la URL invoca a **AWS Lambda `QueryFunction`** (`consulta/`).
3. **Búsqueda Vectorial:** la Lambda consulta `POST /records/namespaces/<ns>/search` para recuperar los fragmentos más relevantes (top_k, score `_score`).
4. **Generación:** `RagContextBuilder` arma el prompt (sistema + contexto con citas + historial + pregunta) y `DeepSeekClient` lo envía a **OpenRouter** (modelo `:free`).
5. **Respuesta:** el LLM genera la respuesta basada en el contexto y se la devuelve a Lambda.
6. **Guardado:** `SessionStore` registra el turno en **Amazon DynamoDB** (tabla `rag-sessions`).
7. **Devolución:** la respuesta final (answer + sources + session_id) se entrega al usuario.