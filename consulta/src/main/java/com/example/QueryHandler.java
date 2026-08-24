package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.example.DeepSeekClient.ChatMessage;
import com.example.RagContextBuilder.Source;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Punto de entrada principal del microservicio de consulta RAG en AWS Lambda.
 *
 * <p>Esta función se expone públicamente a través de una <strong>Lambda Function URL</strong>
 * (formato de payload v2 / {@link APIGatewayV2HTTPEvent}), recibiendo peticiones HTTP POST
 * con preguntas de usuario en formato JSON.</p>
 *
 * <p>Flujo de ejecución:
 * <ol>
 *   <li>Parsea y valida el cuerpo JSON de la petición ({@code {"question":"...", "session_id":"..."}}).</li>
 *   <li>Recupera el historial previo de conversación desde DynamoDB ({@link SessionStore}).</li>
 *   <li>Realiza búsqueda semántica en Pinecone ({@link PineconeSearchClient}) para obtener los chunks relevantes.</li>
 *   <li>Construye el prompt contextualizado con instrucciones estrictas y citas de fuentes ({@link RagContextBuilder}).</li>
 *   <li>Invoca al LLM a través de OpenRouter ({@link DeepSeekClient}) para generar la respuesta.</li>
 *   <li>Persiste el nuevo turno de conversación en DynamoDB.</li>
 *   <li>Retorna una respuesta JSON estructurada con la respuesta, fuentes citadas, ID de sesión y modelo utilizado.</li>
 * </ol>
 * </p>
 */
public class QueryHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger logger = LoggerFactory.getLogger(QueryHandler.class);
    private static final Gson GSON = new Gson();

    private final PineconeSearchClient searchClient;
    private final DeepSeekClient llmClient;
    private final SessionStore sessionStore;

    /**
     * Constructor por defecto utilizado por el entorno de ejecución de AWS Lambda en producción.
     * Carga todos los clientes y secretos a partir de las variables de entorno.
     */
    public QueryHandler() {
        this(PineconeSearchClient.fromEnvironment(),
                DeepSeekClient.fromEnvironment(),
                SessionStore.fromEnvironment());
    }

    /**
     * Constructor con inyección de dependencias para testing unitario e integración.
     *
     * @param searchClient Cliente de búsqueda en Pinecone.
     * @param llmClient Cliente de comunicación con el LLM en OpenRouter.
     * @param sessionStore Almacén de sesiones en DynamoDB.
     */
    QueryHandler(PineconeSearchClient searchClient, DeepSeekClient llmClient, SessionStore sessionStore) {
        this.searchClient = searchClient;
        this.llmClient = llmClient;
        this.sessionStore = sessionStore;
    }

    /**
     * Manejador de la invocación Lambda. Procesa la petición HTTP entrante y devuelve la respuesta RAG.
     *
     * @param event Evento HTTP v2 entregado por la Function URL.
     * @param context Contexto de ejecución de AWS Lambda.
     * @return Respuesta HTTP con código de estado, headers y cuerpo JSON.
     */
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            QueryRequest request = parseRequest(event);
            if (request.question() == null || request.question().isBlank()) {
                return buildErrorResponse(400, "Falta el campo 'question' en el body JSON.");
            }

            // Si el cliente no envía session_id se genera un nuevo UUID para iniciar una sesión limpia.
            String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                    ? UUID.randomUUID().toString() : request.sessionId();

            List<ChatMessage> history = sessionStore.loadSession(sessionId);
            List<com.example.PineconeSearchClient.SearchHit> hits = searchClient.search(request.question());
            List<ChatMessage> messages = RagContextBuilder.buildMessages(request.question(), hits, history);
            String answer = llmClient.chat(messages);
            sessionStore.appendTurn(sessionId, ChatMessage.user(request.question()), ChatMessage.assistant(answer));

            logger.info("Consulta completada exitosamente. session={} hits={}", sessionId, hits.size());
            return buildSuccessResponse(sessionId, answer, RagContextBuilder.buildSources(hits), llmClient.model());

        } catch (IllegalArgumentException e) {
            // Error de validación o sintaxis imputable al cliente: se retorna HTTP 400.
            return buildErrorResponse(400, e.getMessage());
        } catch (Exception e) {
            // Error de servicios externos (Pinecone, OpenRouter, DynamoDB): se registra en logs y retorna HTTP 502.
            logger.error("Error procesando consulta", e);
            return buildErrorResponse(502, "Error interno procesando la consulta.");
        }
    }

    /**
     * Extrae y deserializa el cuerpo JSON del evento HTTP.
     *
     * @param event Evento HTTP entrante.
     * @return Objeto {@link QueryRequest} con los campos extraídos.
     * @throws IllegalArgumentException Si el JSON está malformado.
     */
    static QueryRequest parseRequest(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getBody() == null || event.getBody().isBlank()) {
            return new QueryRequest(null, null);
        }
        try {
            JsonObject body = GSON.fromJson(event.getBody(), JsonObject.class);
            if (body == null) {
                return new QueryRequest(null, null);
            }
            return new QueryRequest(
                    body.has("question") ? body.get("question").getAsString() : null,
                    body.has("session_id") ? body.get("session_id").getAsString() : null);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Body JSON invalido: " + e.getMessage());
        }
    }

    /**
     * Construye una respuesta HTTP 200 OK con el resultado de la consulta.
     *
     * @param sessionId Identificador de la sesión de chat.
     * @param answer Texto de la respuesta generada por el LLM.
     * @param sources Lista de fuentes y fragmentos utilizados.
     * @param model Nombre del modelo LLM que generó la respuesta.
     * @return Objeto {@link APIGatewayV2HTTPResponse} serializado en JSON.
     */
    static APIGatewayV2HTTPResponse buildSuccessResponse(String sessionId, String answer,
                                                         List<Source> sources, String model) {
        JsonObject json = new JsonObject();
        json.addProperty("answer", answer);
        json.add("sources", GSON.toJsonTree(sources));
        json.addProperty("session_id", sessionId);
        json.addProperty("model", model);
        return jsonResponse(200, json.toString());
    }

    /**
     * Construye una respuesta HTTP de error con mensaje descriptivo.
     *
     * @param status Código de estado HTTP (ej. 400, 502).
     * @param message Mensaje explicativo del error.
     * @return Objeto {@link APIGatewayV2HTTPResponse} con estructura {@code {"error":"..."}}.
     */
    static APIGatewayV2HTTPResponse buildErrorResponse(int status, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        return jsonResponse(status, json.toString());
    }

    /**
     * Helper privado para estructurar respuestas HTTP con encabezado {@code Content-Type: application/json}.
     *
     * @param status Código de estado HTTP.
     * @param body Cadena JSON a enviar como cuerpo.
     * @return Objeto {@link APIGatewayV2HTTPResponse}.
     */
    private static APIGatewayV2HTTPResponse jsonResponse(int status, String body) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body)
                .build();
    }

    /**
     * Representa la carga útil de la solicitud recibida por la Lambda.
     *
     * @param question Texto de la pregunta del usuario.
     * @param sessionId Identificador opcional de la sesión de chat para mantener memoria conversacional.
     */
    record QueryRequest(String question, String sessionId) {
    }
}