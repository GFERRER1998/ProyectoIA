# TextChunker

## Propósito

`TextChunker` recibe el texto extraído por PDFBox, lo limpia y lo divide en fragmentos llamados chunks. Estos fragmentos son la unidad que posteriormente se enviará a un modelo de embeddings y a Pinecone.

## Por qué hace falta

Un PDF no siempre devuelve texto limpio. Puede contener:

- Saltos de página representados por `\f`.
- Retornos de carro `\r`.
- Muchos espacios o tabs.
- Caracteres de control.
- Líneas vacías repetidas.

Además, un documento completo puede ser demasiado grande para procesarlo como una sola entrada. Los chunks permiten buscar y recuperar partes concretas del documento.

## Paso a paso

### 1. Se leen los parámetros

`process(String text)` consulta estas variables de entorno:

| Variable | Valor por defecto | Significado |
|---|---:|---|
| `CHUNK_SIZE` | 600 | Tokens estimados por chunk |
| `CHUNK_OVERLAP` | 60 | Tokens compartidos entre chunks |
| `CHARS_PER_TOKEN` | 4 | Aproximación para convertir caracteres a tokens |

La aproximación no es un conteo exacto del tokenizer del modelo. Sirve para mantener un tamaño predecible sin añadir todavía una dependencia de tokenización.

### 2. Se valida la configuración

El tamaño debe ser positivo. El overlap puede ser cero, pero debe ser menor que `CHUNK_SIZE`. Si el overlap fuera igual o mayor, el algoritmo podría no avanzar.

### 3. Se normaliza el texto

`normalize` realiza estas operaciones:

1. Convierte saltos de página en saltos de línea.
2. Convierte retornos de carro en saltos de línea.
3. Elimina caracteres no imprimibles.
4. Reduce grupos de espacios y tabs a un espacio.
5. Elimina espacios alrededor de saltos de línea.
6. Conserva como máximo una línea vacía entre párrafos.
7. Elimina espacios al principio y al final.

Si el texto es nulo, vacío o solo contiene ruido, devuelve una cadena vacía.

### 4. Se calcula el tamaño aproximado

Con los valores por defecto:

```text
600 tokens * 4 caracteres/token = aproximadamente 2400 caracteres
60 tokens * 4 caracteres/token = aproximadamente 240 caracteres de overlap
```

### 5. Se crea cada chunk

El algoritmo toma una ventana de caracteres y busca el último espacio antes del límite. De esta forma no corta una palabra en dos.

Si el texto termina antes del límite, se crea un único chunk final.

### 6. Se conserva overlap

El siguiente chunk comienza un poco antes del final del anterior. El solapamiento ayuda a que una idea dividida entre dos chunks conserve contexto en ambos.

El inicio del siguiente chunk se ajusta al límite de una palabra para no comenzar en mitad de ella.

### 7. Se devuelven resultados y estadísticas

`ChunkingResult` contiene:

- Texto normalizado.
- Lista de chunks.
- Configuración usada.
- Mínimo estimado de tokens.
- Máximo estimado de tokens.

## Ejemplo conceptual

```text
Texto completo:  A B C D E F G H I J

Chunk 1:         A B C D E F
Chunk 2:               E F G H I J
```

`E F` representa el overlap. Los valores reales se calculan usando caracteres y límites de palabras.

## Reglas de seguridad

- Existe un límite máximo de 10.000 chunks por documento.
- Un texto vacío no genera llamadas externas.
- Una configuración inválida no se acepta silenciosamente cuando se pasa directamente al método de procesamiento.

## Contexto para IA

Cuando una IA analice este archivo debe tratar los chunks como texto todavía no vectorizado. `TextChunker` no llama a Grok ni a Pinecone y no debe contener credenciales, HTTP ni lógica de persistencia.
