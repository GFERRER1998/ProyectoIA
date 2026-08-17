# Contexto de Arquitectura: RAG en AWS (Retrieval-Augmented Generation)

## Resumen General
Esta arquitectura describe un sistema de Generación Aumentada por Recuperación (RAG) serverless implementado en AWS. El sistema consta de dos flujos principales: el **Flujo de Ingestión e Indexación de Documentos** (implementado) y el **Flujo de Consulta / Respuesta (RAG)** (futuro), apoyados por servicios auxiliares de la nube.

> Estado: **ingestión completa y verificada (v1)**. Consulta RAG, DynamoDB, Cognito y API Gateway son fases futuras.

---

## Servicios de AWS y sus Roles en la Arquitectura

### 1. Entrada y Salida (Interfaces)
* **Amazon API Gateway (HTTPS):** *(futuro)* Punto de entrada público para el flujo de consulta.

### 2. Cómputo y Lógica Principal
* **AWS Lambda `PdfTextExtractorFunction` (Java 17, implementado):**
  * En la **ingestión**: se activa por el evento `s3:ObjectCreated:*` del bucket (prefijo `documents/`, sufijo `.pdf`). Descarga el PDF, extrae texto (PDFBox), divide en chunks (`TextChunker`) y envía los registros a Pinecone (`PineconeClient`).
  * En las **consultas**: *(futuro)* coordinará búsqueda vectorial, LLM e historial.

### 3. Almacenamiento de Archivos y Documentos
* **Amazon S3 — Bucket `rag-documents-683023468765` (implementado):**
  * `documents/`: **Aquí se suben los PDFs** (`.pdf`). El evento `s3:ObjectCreated` (prefijo `documents/`, sufijo `.pdf`) dispara la Lambda automáticamente.
  * `processed/`, `embeddings/`, `backups/`: *(reservados)* para fases futuras.

### 4. Base de Datos Vectorial (RAG / Knowledge Base)
* **Pinecone — índice `rag-index` (implementado):** serverless, región `us-east-1`, métrica `cosine`, 1024 dimensiones, **embedding integrado** (`field_map`: campo `text`). Almacena los registros y genera los vectores internamente al recibir el NDJSON del upsert. La API key vive en **AWS Secrets Manager** (`pinecone/pdf-extractor`).

### 5. Inteligencia Artificial / Modelos Generativos (LLM & Embeddings)
* **Embeddings (implementado):** los genera **Pinecone server-side** (modelo del índice, `llama-text-embed-v2` o similar). La Lambda **no** llama a ningún proveedor de embeddings.
* **Grok API (futuro):** LLM para generar respuestas en el flujo de consulta (Claude, Llama, Mistral, Nova, etc. como alternativas).

### 6. Persistencia de Sesión e Historial
* **Amazon DynamoDB:** *(futuro)* historial de conversaciones, datos de usuarios y sesiones activas.

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

### B. Flujo de Consulta (RAG) — *futuro*
1. **Envío:** El **Usuario** realiza una pregunta desde la App Web.
2. **Recepción:** **Amazon API Gateway** recibe la solicitud HTTPS.
3. **Procesamiento:** **AWS Lambda** procesa la solicitud.
4. **Búsqueda Vectorial:** Lambda consulta `POST /records/namespaces/<ns>/search` para recuperar los fragmentos más relevantes (el índice admite búsqueda por texto; verificado).
5. **Generación:** Lambda envía la pregunta original + el contexto recuperado a **Grok api**.
6. **Respuesta:** Grok genera la respuesta basada en el contexto recibido y se la devuelve a Lambda.
7. **Guardado:** Lambda registra la conversación/interacción en **Amazon DynamoDB**.
8. **Devolución:** La respuesta final se entrega al usuario a través de API Gateway.