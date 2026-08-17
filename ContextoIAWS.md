# Infraestructura AWS (estado actual)

## Bucket de documentos

**`rag-documents-683023468765`** (región `us-east-1`) — creado y gestionado por el stack CloudFormation `pdf-extractor-lambda-stack`.

| Ruta | Uso |
|---|---|
| `documents/` | **Aquí se suben los PDFs.** El evento `s3:ObjectCreated:*` (prefijo `documents/`, sufijo `.pdf`) dispara la Lambda automáticamente |
| `processed/` | (reservado) Archivos procesados |
| `embeddings/` | (reservado) Exportaciones de vectores |
| `backups/` | (reservado) Copias de seguridad |

> Los prefijos `processed/`, `embeddings/` y `backups/` no se crean todavía: se crean solos al subir el primer archivo o en fases futuras.

## Otros recursos

| Recurso | Nombre | Región |
|---|---|---|
| Lambda | `pdf-extractor-lambda-stac-PdfTextExtractorFunction-*` | us-east-1 |
| Stack CloudFormation | `pdf-extractor-lambda-stack` | us-east-1 |
| Secreto Pinecone | `pinecone/pdf-extractor` | us-east-1 |
| Bucket interno SAM | `aws-sam-cli-managed-default-samclisourcebucket-*` | us-east-1 |
| Índice Pinecone (externo) | `rag-index` (serverless, cosine, 1024 dims) | us-east-1 |