# ProyectoIA — Ingestión RAG de PDFs (AWS + Pinecone)

Pipeline serverless que convierte PDFs en vectores semánticos listos para búsqueda RAG.

```
S3 (documents/*.pdf) ──trigger──> Lambda (Java 17) ──> Pinecone (embeddings integrados)
                                    │
                                    ├─ PDFBox: extracción de texto
                                    ├─ TextChunker: normalización + división en chunks
                                    └─ PineconeClient: upsert NDJSON (campo "text")
```

## Arquitectura actual (v1)

| Componente | Rol |
|---|---|
| **S3** `rag-documents-683023468765` | Almacén de PDFs. El evento `s3:ObjectCreated:*` (prefijo `documents/`, sufijo `.pdf`) dispara la Lambda |
| **Lambda** `pdf-extractor-lambda-stac-PdfTextExtractorFunction-*` | Extrae texto, divide en chunks y los indexa en Pinecone |
| **Pinecone** `rag-index` (serverless, us-east-1, cosine, 1024 dims) | Base vectorial con **embedding integrado** (el vector lo genera Pinecone, no la app) |
| **Secrets Manager** `pinecone/pdf-extractor` | Guarda la API key de Pinecone (`{"apiKey":"..."}`); la Lambda la lee por IAM, nunca en código ni logs |

Flujo por documento:

1. Se sube un PDF a `s3://rag-documents-683023468765/documents/<archivo>.pdf`.
2. S3 invoca la Lambda automáticamente (filtro: prefijo `documents/`, sufijo `.pdf`).
3. La Lambda descarga el PDF, extrae el texto con PDFBox y lo normaliza/divide con `TextChunker` (chunks ~600 tokens con overlap de 60).
4. `PineconeClient` envía los chunks como NDJSON a `POST /records/namespaces/<ns>/upsert` con el texto en el campo `text` (el campo mapeado por `field_map` del índice).
5. Pinecone genera los embeddings server-side y los indexa (asíncrono: buscables en segundos).
6. Los IDs son determinísticos (`sha256(bucket+key+etag)[0:16]_chunk_N`): re-subir el mismo archivo **sobrescribe**, no duplica.

## Dónde subir los PDFs

```bash
aws s3 cp archivo.pdf s3://rag-documents-683023468765/documents/
```

Solo se procesan archivos `.pdf` dentro de `documents/`. Cualquier otra ubicación o extensión se ignora (no invoca la Lambda).

## Componentes del código

| Clase | Responsabilidad |
|---|---|
| `PdfTextExtractor` | Orquestación: evento S3 → PDFBox → TextChunker → PineconeClient |
| `TextChunker` | Normaliza texto y lo divide en chunks con overlap |
| `PineconeClient` | Construye IDs/metadata y hace el upsert NDJSON con reintentos |

## Configuración de la Lambda (variables de entorno)

| Variable | Origen | Descripción |
|---|---|---|
| `PINECONE_SECRET_ARN` | Parámetro CloudFormation | ARN del secreto en Secrets Manager |
| `PINECONE_INDEX_HOST` | Parámetro CloudFormation | Host HTTPS del índice (sin `/` final) |
| `PINECONE_NAMESPACE` | Parámetro CloudFormation (default `documents-dev`) | Namespace destino |
| `PINECONE_TEXT_FIELD` | Parámetro CloudFormation (default `text`) | Campo del registro que el índice usa para embedding (`field_map`) |
| `CHUNK_SIZE`, `CHUNK_OVERLAP`, `CHARS_PER_TOKEN` | Opcionales | Ajustes de chunking (defaults 600/60/4) |

## Secretos

### Local (desarrollo)
- Copiar `.env.example` a `.env` y completar `PINECONE_API_KEY`, `PINECONE_INDEX_HOST`.
- `.env` está en `.gitignore` — **nunca** commitearlo.

### AWS (producción)
- Secreto: `pinecone/pdf-extractor` (creado con `aws secretsmanager create-secret`).
- Acepta la key plana o JSON con `apiKey` / `PINECONE_API_KEY`.
- El rol de la Lambda solo tiene `secretsmanager:GetSecretValue` sobre ese ARN.

## Despliegue

```bash
sam build
sam deploy --region us-east-1 \
  --parameter-overrides \
    "BucketName=rag-documents-683023468765 \
     PineconeSecretArn=arn:aws:secretsmanager:us-east-1:<cuenta>:secret:pinecone/pdf-extractor-<sufijo> \
     PineconeIndexHost=https://rag-index-xxxx.svc.<region>.pinecone.io \
     PineconeNamespace=documents-dev \
     PineconeTextField=text"
```

Salidas del stack: ARN de la Lambda y nombre del bucket.

## Verificación

- **Logs:** consola CloudWatch, grupo `/aws/lambda/pdf-extractor-lambda-stac-PdfTextExtractorFunction-*`. Logs clave: `Extracción completada`, `chunks generados`, `Pinecone upsert completado. Registros: N`.
- **Pinecone:** búsqueda semántica:
  ```bash
  curl -X POST "https://<HOST>/records/namespaces/documents-dev/search" \
    -H "Api-Key: <KEY>" -H "X-Pinecone-Api-Version: 2025-10" \
    -H "Content-Type: application/json" \
    -d '{"query":{"inputs":{"text":"tu consulta"},"top_k":5},"fields":["source_key","chunk_index"]}'
  ```
- **Tests:** `set -a; source .env; set +a; mvn test` (unitarios + integración contra Pinecone real; el test de integración se omite si faltan variables).

## Docs detallados

| Documento | Contenido |
|---|---|
| `ContextIA.md` | Arquitectura completa (fases actuales y futuras) |
| `PdfTextExtractor.md` | Detalle de la Lambda |
| `TextChunker.md` | Normalización y chunking |
| `PineconeClient.md` | Contrato con Pinecone (endpoint, campo `text`, reintentos) |
| `sam_explicacion.md` | Explicación humana de `template.yaml` y `samconfig.toml` |

## Fases futuras (no implementadas)

- API Gateway + Lambda de consulta (RAG con LLM).
- DynamoDB (historial de conversaciones).
- Cognito (autenticación de `consulta/`, implementado).
- Embeddings/LLM vía Grok API (hoy los embeddings los genera Pinecone internamente).
