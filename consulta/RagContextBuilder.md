# RagContextBuilder

## Propósito

`RagContextBuilder` es el componente encargado de transformar los fragmentos (chunks) recuperados por `PineconeSearchClient` en un prompt altamente estructurado, coherente e inteligible para el LLM.

Esta clase es **lógica pura**: no tiene dependencias con AWS SDK, clientes HTTP ni bases de datos. Su diseño funcional y determinista facilita el ajuste de directivas del sistema y la realización de pruebas unitarias exhaustivas.

## Diseño del System Prompt

El prompt del sistema (`SYSTEM_PROMPT`) implementa reglas de ingeniería de prompts rigurosamente diseñadas para evitar alucinaciones y forzar la trazabilidad documental:

```text
Eres un asistente RAG que responde preguntas usando exclusivamente el contexto proporcionado.
Reglas:
1. Responde solo con informacion que este en el contexto. Si el contexto no la contiene, di que no tienes esa informacion.
2. Al final de cada afirmacion apoyada en una fuente, cita la fuente entre corchetes, por ejemplo [documents/x.pdf (chunk 3)].
3. Responde en el idioma de la pregunta.
4. No inventes datos, cifras ni fuentes.

Contexto:
%s
```

### Justificación de las reglas:
- **Regla 1 (Fidelidad al contexto)**: Si el usuario pregunta algo que no está en los PDFs indexados, el modelo previene invenciones declarando explícitamente la falta de información.
- **Regla 2 (Citas explícitas)**: Obliga al LLM a relacionar cada afirmación con la fuente exacta (`[documento (chunk N)]`), permitiendo al usuario final auditar la respuesta.
- **Regla 3 (Multilingüe)**: El modelo detecta y responde automáticamente en el idioma en que el usuario formuló la pregunta.
- **Regla 4 (Anti-alucinación)**: Prohíbe estimaciones ficticias o fuentes no provistas.

## Paso a paso

### 1. Formateo del bloque de contexto

El método `buildContextBlock(List<SearchHit> hits)` toma los fragmentos recuperados y genera un bloque legible numerado:

```text
[Fuente 1] documents/attention.pdf (chunk 3)
El mecanismo de autoatención permite a los modelos relacionar distintas posiciones...

[Fuente 2] documents/attention.pdf (chunk 4)
La atención multi-cabezal permite al modelo atender conjuntamente a información...
```

Si la lista de hits está vacía (por ejemplo, si no hubo coincidencia semántica relevante), genera un mensaje neutro: `No se encontraron documentos relevantes para esta pregunta.`.

### 2. Ensamblado de la secuencia de mensajes

`buildMessages(String question, List<SearchHit> hits, List<ChatMessage> history)` construye la lista en el orden exacto que espera la API de Chat Completions:

```text
1. ChatMessage.system(...)    -> System Prompt con el contexto inyectado
2. [Opcional] Historial       -> Pares user/assistant de turnos anteriores
3. ChatMessage.user(question) -> Pregunta actual del usuario
```

### 3. Extracción y deduplicación de fuentes

`buildSources(List<SearchHit> hits)` procesa los fragmentos y devuelve una lista de objetos `Source` únicos identificados por la clave compuesta `source_key#chunk_index`:
- Conserva el orden de mayor a menor relevancia determinado por Pinecone.
- Elimina duplicados si el mismo fragmento fue recuperado varias veces.
- Filtra elementos con origen nulo.

Esta lista es retornada en la respuesta JSON de la API para que interfaces web o clientes móviles puedan mostrar tarjetas de referencia o enlaces directos al documento.

## Estructuras de Datos

### `Source` (Record)
```java
public record Source(String sourceKey, String chunkIndex, double score) {
}
```

| Campo | Tipo | Descripción |
|---|---|---|
| `sourceKey` | `String` | Ruta del archivo PDF en S3 (ej. `documents/paper.pdf`) |
| `chunkIndex` | `String` | Número ordinal del fragmento en el documento |
| `score` | `double` | Puntuación de similitud semántica coseno (0.0 a 1.0) |

## Contexto para IA

Cuando una IA analice o modifique este componente:

- **Sin dependencias**: No agregar clientes de red, librerías pesadas ni llamadas a APIs externas en esta clase. Debe mantenerse pura y desacoplada.
- **Formato de Citas**: Si se cambia el formato de cita en el `SYSTEM_PROMPT` (ej. corchetes `[...]`), asegurarse de que coincida con lo esperado por las interfaces de usuario que consuman la API.
- **Inyección de Prompts**: Proteger la separación entre las instrucciones del sistema y las entradas del usuario para evitar vulnerabilidades de Prompt Injection.
