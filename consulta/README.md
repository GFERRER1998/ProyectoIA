# Microservicio de Consulta (RAG) — PENDIENTE

Segundo microservicio del monorepo: consultas RAG sobre los documentos indexados por `ingesta/`.

Stack previsto:

- **Entrada:** Lambda Function URL (o API Gateway) para recibir preguntas del usuario.
- **Búsqueda vectorial:** Pinecone (índice `rag-index`, namespace `documents-dev`).
- **LLM:** DeepSeek vía OpenRouter (modelos `:free`).
- **Sesiones:** DynamoDB (historial de conversaciones).

Pendiente de implementación.