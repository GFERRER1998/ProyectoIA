# PineconeClient

## Propósito

`PineconeClient` es la capa que conecta la Lambda con Pinecone. Recibe chunks ya normalizados, crea registros con metadata y los envía al índice que tiene embedding integrado.

Esta clase no extrae PDFs ni divide texto. Su entrada esperada es la salida de `TextChunker`.

## Importante: endpoint utilizado

Para un índice Pinecone con embedding integrado, el contrato correcto es:

```text
POST https://<INDEX_HOST>/records/namespaces/<NAMESPACE>/upsert
```

El cuerpo usa NDJSON, es decir, un objeto JSON por línea:

```json
{"_id":"abc_chunk_0","text":"Texto del chunk","document_id":"abc"}
```

Pinecone lee el campo mapeado en `field_map` (en `rag-index` el campo configurado es `text`, NO `chunk_text`) y genera el vector con el modelo configurado en el índice, por ejemplo `llama-text-embed-v2`.

El nombre del campo se lee desde la variable `PINECONE_TEXT_FIELD` (default `text`). Si el índice se creara con otro `field_map`, solo se cambia esa variable, no el código.

No se utiliza `/vectors/upsert` con un campo `embed`. Ese endpoint se utiliza cuando la aplicación ya posee los valores numéricos del vector.

## Paso a paso

### 1. Se crea el cliente

El constructor recibe:

- `apiKey` para autenticar.
- `indexHost` para saber a qué índice conectarse.
- `namespace` para separar los registros.

También prepara un `HttpClient` reutilizable y configura timeout de conexión y request.

### 2. Se cargan secretos en Lambda

`fromEnvironment` obtiene:

- `PINECONE_SECRET_ARN` desde variables de entorno.
- La API key desde AWS Secrets Manager.
- `PINECONE_INDEX_HOST` desde variables de entorno.
- `PINECONE_NAMESPACE` desde variables de entorno.
- `PINECONE_TEXT_FIELD` desde variables de entorno (default `text`).

El secreto puede contener directamente la key o un JSON:

```json
{"apiKey":"valor-secreto"}
```

La API key nunca se escribe en el código ni en logs.

### 3. Se crea un ID de documento

El `documentId` se calcula con SHA-256 usando:

```text
bucket + separador + key + separador + etag
```

El resultado no expone los datos originales y es estable para el mismo contenido.

### 4. Se crea un ID por chunk

Cada chunk utiliza:

```text
primeros-16-caracteres-del-documentId + _chunk_ + indice
```

Ejemplo:

```text
a1b2c3d4e5f60718_chunk_0
a1b2c3d4e5f60718_chunk_1
```

Si Lambda reintenta el evento, Pinecone vuelve a recibir los mismos IDs y sobrescribe los registros. Así se evita duplicar chunks.

### 5. Se construye la metadata

Cada registro conserva información para rastrear el origen:

| Campo | Uso |
|---|---|
| `document_id` | Agrupar todos los chunks del documento |
| `source_bucket` | Saber de qué bucket viene |
| `source_key` | Saber la ruta del PDF |
| `chunk_index` | Conservar el orden original |
| `content_type` | Tipo del archivo |
| `ingested_at` | Fecha de indexación |

El texto en sí no se duplica en metadata: vive en el campo `text` que el índice usa para el embedding.

### 6. Se dividen los registros en lotes

La clase utiliza lotes de hasta 100 registros. Esto controla el tamaño del request y limita el impacto de un error temporal.

### 7. Se construye el request HTTP

Los headers principales son:

```text
Content-Type: application/x-ndjson
Api-Key: <secreto>
X-Pinecone-Api-Version: 2025-10
```

El cuerpo nunca se escribe en los logs porque contiene texto potencialmente privado.

### 8. Se envía y se revisa el estado

Todo estado HTTP `2xx` se considera correcto. Pinecone no necesita que la Lambda procese una respuesta de contenido para continuar.

Los errores `429` y `5xx` se consideran temporales. Los errores de configuración o payload, como `400`, se propagan inmediatamente.

### 9. Se aplican reintentos

Hay hasta tres intentos con backoff:

```text
Intento 1 -> espera 250 ms
Intento 2 -> espera 500 ms
Intento 3 -> falla y propaga la excepción
```

Si se interrumpe el hilo, se restaura el estado de interrupción y se detiene el proceso.

## Relación con la Lambda

El flujo completo queda así:

```text
PdfTextExtractor
  -> extrae texto con PDFBox
  -> TextChunker normaliza y divide
  -> PineconeClient crea IDs y metadata
  -> Pinecone genera embeddings integrados
  -> Pinecone almacena los registros
```

## Contexto para IA

Cuando una IA analice este archivo debe respetar estas reglas:

- No agregar API keys al código.
- No imprimir el cuerpo NDJSON en logs.
- Mantener IDs determinísticos para conservar idempotencia.
- No cambiar el endpoint sin verificar si el índice usa embedding integrado.
- Mantener separadas extracción, chunking y persistencia.
