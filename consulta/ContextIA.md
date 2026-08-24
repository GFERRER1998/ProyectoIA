# Contexto de Arquitectura: Microservicio de Consulta (RAG)

## Resumen General

Este microservicio implementa el pipeline serverless de **Consulta y Generación Aumentada por Recuperación (RAG)**. Permite a los usuarios realizar preguntas en lenguaje natural a través de una API HTTPS pública (Lambda Function URL), recupera automáticamente los fragmentos documentales más relevantes previamente indexados en la base de datos vectorial Pinecone, envía el prompt enriquecido a un LLM vía OpenRouter y persiste el historial conversacional en Amazon DynamoDB.

> Estado: **Microservicio de Consulta implementado y verificado (v2)**. Articula con el microservicio de **Ingesta (v1)**.

---

## Diagrama de Flujo de Consulta

```text
Usuario (HTTP POST)
   │
   ▼
[Lambda Function URL] (CORS habilitado, Auth: NONE / API Gateway compatible)
   │
   ▼
[QueryHandler.java] (AWS Lambda - Java 17)
   │
   ├── 1. SessionStore.java ───────────> Amazon DynamoDB (tabla rag-sessions)
   │      (Recupera últimos turnos)
   │
   ├── 2. PineconeSearchClient.java ───> Pinecone Serverless (rag-index)
   │      (Búsqueda vectorial server-side con embedding integrado)
   │
   ├── 3. RagContextBuilder.java
   │      (Construye prompt con reglas estrictas y citas [Fuente N])
   │
   ├── 4. DeepSeekClient.java ─────────> OpenRouter API (POST /chat/completions)
   │      (Genera respuesta con citas fácticas)
   │
   ├── 5. SessionStore.java ───────────> Amazon DynamoDB
   │      (Guarda el nuevo turno con timestamps)
   │
   ▼
Respuesta JSON:
{
  "answer": "Respuesta con citas [documents/x.pdf (chunk 3)]",
  "sources": [{"sourceKey": "documents/x.pdf", "chunkIndex": "3", "score": 0.89}],
  "session_id": "uuid-sesion",
  "model": "nvidia/nemotron-3-ultra-550b-a55b:free"
}
```

---

## Componentes del Microservicio

| Archivo | Documentación Detallada | Responsabilidad Principal |
|---|---|---|
| `QueryHandler.java` | [QueryHandler.md](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/QueryHandler.md) | Handler Lambda, validación, orquestación del flujo y serialización HTTP |
| `PineconeSearchClient.java` | [PineconeSearchClient.md](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/PineconeSearchClient.md) | Búsqueda semántica en Pinecone (endpoint `/records/namespaces/{ns}/search`) |
| `RagContextBuilder.java` | [RagContextBuilder.md](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/RagContextBuilder.md) | Inyección de contexto, deduplicación de fuentes y System Prompt anti-alucinaciones |
| `DeepSeekClient.java` | [DeepSeekClient.md](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/DeepSeekClient.md) | Invocación de LLM en OpenRouter con reintentos y soporte de modelos free |
| `SessionStore.java` | [SessionStore.md](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/SessionStore.md) | Persistencia de sesiones y política de recorte FIFO en DynamoDB |
| `template.yaml` | [sam_explicacion.md](file:///c:/Users/gnzlf/Desktop/ProyectoIA/consulta/sam_explicacion.md) | Definición de infraestructura serverless con AWS SAM |

---

## Reglas y Directivas para Agentes de IA

Cuando un agente de IA trabaje con este microservicio:
1. **Separación de Capas**: Respetar las responsabilidades de cada componente; no introducir llamadas de red ni lógica de DynamoDB dentro de `RagContextBuilder`.
2. **Seguridad y Privacidad**: Nunca imprimir API keys, secretos de Secrets Manager ni textos crudos de conversaciones en logs de CloudWatch.
3. **Manejo de Errores**: Todo error en servicios externos (Pinecone, OpenRouter, DynamoDB) debe retornar `HTTP 502` genérico al cliente y volcar la traza completa a `logger.error`.
4. **Idempotencia y Trazabilidad**: Las fuentes citadas deben conservar siempre `sourceKey`, `chunkIndex` y `score` para permitir auditoría del usuario final.
