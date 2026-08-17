# PdfTextExtractor

## Propósito

`PdfTextExtractor` es el punto de entrada de AWS Lambda. AWS S3 lo invoca cuando se crea un objeto y la clase coordina todo el procesamiento de cada PDF.

El flujo actual es:

```text
Evento S3 -> descargar PDF -> extraer texto -> normalizar y dividir -> Pinecone
```

## Paso a paso

### 1. Lambda recibe el evento S3

El método `handleRequest` recibe un `S3Event`. El evento contiene uno o varios registros. Cada registro informa:

- El nombre del bucket.
- La clave o ruta del archivo.
- El `eTag`, usado para identificar la versión del contenido.

La Lambda recorre todos los registros. Esto evita procesar solamente el primer archivo cuando S3 entrega un evento con varios objetos.

### 2. Se identifica y valida el archivo

La clave puede llegar codificada por URL, por eso se decodifica antes de usarla. Después se comprueba que termine en `.pdf`.

Los archivos que no son PDF se omiten y no generan llamadas a PDFBox ni a Pinecone.

### 3. Se descarga el PDF desde S3

Se crea un `GetObjectRequest` con el bucket y la clave. `S3Client` descarga el contenido y `Loader.loadPDF` lo transforma en un `PDDocument`.

Los recursos se cierran automáticamente con `try-with-resources`.

### 4. Se extrae el texto

`PDFTextStripper` recorre las páginas y devuelve un `String`. Esta operación solo funciona cuando el PDF tiene una capa de texto.

Un PDF escaneado puede no tener texto utilizable. En ese caso la Lambda registra una advertencia y devuelve un resultado controlado. El OCR queda para una fase posterior.

### 5. Se normaliza y divide el texto

El texto se entrega a `TextChunker`:

```java
TextChunker.ChunkingResult chunking = TextChunker.process(extractedText);
```

`TextChunker` elimina ruido de PDF, calcula chunks de tamaño controlado y conserva el texto original de cada fragmento.

### 6. Se envían los chunks a Pinecone

Si existen chunks, `PineconeClient.upsertChunks` los convierte en registros y los envía al índice Pinecone con embedding integrado.

Si el upsert falla, la excepción sube hasta Lambda. Esto permite que la configuración de reintentos de AWS vuelva a procesar el evento.

### 7. Se registra el resultado

CloudWatch recibe métricas como:

- Archivo procesado.
- Caracteres extraídos.
- Cantidad de chunks.
- Rango estimado de tokens.

No se registra la API key. El preview de texto solo se escribe en nivel `debug`.

## Responsabilidades

`PdfTextExtractor` coordina el flujo. No debe contener la lógica detallada de limpieza, chunking ni HTTP de Pinecone.

| Componente | Responsabilidad |
|---|---|
| `PdfTextExtractor` | Coordinar S3, PDFBox, TextChunker y Pinecone |
| `TextChunker` | Normalizar y dividir texto |
| `PineconeClient` | Crear y enviar registros a Pinecone |

## Configuración necesaria

La Lambda usa estas variables:

- `PINECONE_SECRET_ARN`: ARN del secreto con la API key.
- `PINECONE_INDEX_HOST`: host HTTPS del índice.
- `PINECONE_NAMESPACE`: namespace de destino.

La identidad de ejecución necesita permiso `secretsmanager:GetSecretValue` sobre el secreto.

## Contexto para IA

Cuando una IA analice este archivo debe entender que `PdfTextExtractor` es la orquestación principal. No debe mover la lógica de chunking o de red a esta clase salvo que exista una razón clara. El orden correcto es extracción, normalización, chunking y persistencia.
