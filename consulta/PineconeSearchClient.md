# PineconeSearchClient

## Propósito

`PineconeSearchClient` es la capa que conecta el microservicio de consulta con la base de datos vectorial Pinecone. Recibe una pregunta en lenguaje natural, consulta el índice serverless con **embedding integrado** y devuelve los fragmentos (chunks) más relevantes ordenados por similitud semántica (score).

Esta clase no orquesta la llamada al LLM ni almacena sesiones en base de datos. Su única responsabilidad es transformar una búsqueda textual en una lista de objetos `SearchHit`.

## Importante: endpoint y embedding integrado

El índice `rag-index` está configurado con **embedding integrado** (por ejemplo, con el modelo `llama-text-embed-v2`). Por esta razón, el microservicio de consulta no necesita invocar un servicio externo de embeddings (como OpenAI Embeddings o Bedrock).

El contrato REST oficial utilizado es:

```text
POST https://<INDEX_HOST>/records/namespaces/<NAMESPACE>/search
```

El cuerpo de la petición se estructura con la propiedad `inputs`:

```json
{
  "query": {
    "inputs": {
      "text": "¿Qué es el mecanismo de autoatención?"
    },
    "top_k": 5
  },
  "fields": [
    "text",
    "source_key",
    "chunk_index",
    "document_id"
  ]
}
```

Pinecone realiza las siguientes acciones server-side:
1. Vectoriza el texto contenido en `inputs.<textField>` utilizando el modelo configurado en el índice.
2. Compara el vector resultante contra los chunks almacenados mediante distancia coseno.
3. Devuelve los mejores `top_k` aciertos con sus respectivos scores y los campos de metadatos solicitados en `fields`.

## Paso a paso

### 1. Se crea el cliente

El constructor recibe:
- `apiKey`: API Key de Pinecone obtenida de Secrets Manager.
- `indexHost`: host HTTPS del índice (ej. `https://rag-index-xxx.svc.pinecone.io`).
- `namespace`: namespace del índice donde se indexaron los documentos (default `documents-dev`).
- `textField`: nombre del campo que contiene el texto del chunk (default `text`).
- `topK`: cantidad máxima de fragmentos a recuperar (default `5`).
- `httpClient`: cliente HTTP reutilizable con timeout de conexión.
- `requestTimeout`: timeout total para la llamada HTTP (30 segundos).

### 2. Se cargan secretos en Lambda

`fromEnvironment` obtiene:
- `PINECONE_SECRET_ARN`: ARN del secreto en AWS Secrets Manager.
- `PINECONE_INDEX_HOST`: host del índice desde variables de entorno.
- `PINECONE_NAMESPACE`: namespace de búsqueda.
- `PINECONE_TEXT_FIELD`: campo mapeado (por defecto `text`).
- `PINECONE_TOP_K`: límite de fragmentos recuperados.

### 3. Se valida la pregunta

El método `search(String question)` valida que la consulta no sea nula ni esté vacía, evitando llamadas innecesarias a la API.

### 4. Se construye el request HTTP

Se configuran los headers obligatorios:
```text
Content-Type: application/json
Api-Key: <secreto>
X-Pinecone-Api-Version: 2025-10
```

El namespace se codifica por URL para evitar conflictos con caracteres especiales.

### 5. Se parsea la respuesta

La estructura devuelta por Pinecone es analizada por `parseSearchResponse`:

```json
{
  "result": {
    "hits": [
      {
        "_id": "a1b2c3d4e5f60718_chunk_0",
        "_score": 0.892,
        "fields": {
          "text": "El mecanismo de autoatención permite...",
          "source_key": "documents/attention.pdf",
          "chunk_index": "0",
          "document_id": "a1b2c3d4e5f60718"
        }
      }
    ]
  }
}
```

Cada elemento se convierte en un objeto inmutable `SearchHit` que contiene:
- `id`: identificador único del chunk.
- `text`: texto recuperado del fragmento para inyectar en el contexto.
- `score`: métrica de relevancia semántica.
- `sourceKey`: ruta original del archivo PDF en Amazon S3.
- `chunkIndex`: índice del fragmento dentro del PDF.
- `documentId`: hash identificador único del documento.

### 6. Se aplican reintentos

Ante errores transitorios de red (`5xx`) o límites de concurrencia (`429`), se ejecutan hasta 3 intentos con backoff exponencial (250 ms, 500 ms y 1000 ms).

## Relación con el flujo RAG

```text
QueryHandler
  -> envía question a PineconeSearchClient
  -> Pinecone vectoriza server-side y busca los chunks más cercanos
  -> PineconeSearchClient devuelve List<SearchHit> ordenada por score descendente
  -> RagContextBuilder usa los hits para armar el prompt con citas
```

## Configuración y Variables de Entorno

| Variable | Valor por defecto | Descripción |
|---|---|---|
| `PINECONE_SECRET_ARN` | *(Obligatorio)* | ARN del secreto con la API Key en Secrets Manager |
| `PINECONE_INDEX_HOST` | *(Obligatorio)* | URL HTTPS del índice Pinecone |
| `PINECONE_NAMESPACE` | `documents-dev` | Namespace donde residen los chunks |
| `PINECONE_TEXT_FIELD` | `text` | Campo configurado en el `field_map` del índice |
| `PINECONE_TOP_K` | `5` | Número de chunks relevantes a retornar |

## Contexto para IA

Cuando una IA analice o modifique este archivo debe respetar estas reglas:

- **Endpoint de búsqueda**: No utilizar `/query` con vectores numéricos. El índice tiene embedding integrado y requiere `/records/namespaces/{ns}/search` con el bloque `query.inputs.<textField>`.
- **Campos solicitados**: Asegurar siempre la inclusión de `text`, `source_key`, `chunk_index` y `document_id` en el array `fields` para que las citas del RAG puedan construirse.
- **Seguridad**: No imprimir los payloads de búsqueda ni las API Keys en los logs de CloudWatch.
- **Top K**: Mantener valores de `top_k` moderados (3 a 8) para optimizar latencia, costos y evitar sobrecargar la ventana de contexto del LLM.
