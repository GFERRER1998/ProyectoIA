package com.example;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.example.DeepSeekClient.ChatMessage;
import com.example.PineconeSearchClient.SearchHit;
import com.example.RagContextBuilder.Source;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias para el controlador de consultas {@link QueryHandler}.
 * Valida el parseo del payload HTTP v2, la autenticación JWT (200 / 401) y
 * la construcción de respuestas exitosas y de error.
 *
 * <p>Los colaboradores de {@code QueryHandler} (Pinecone, DeepSeek, SessionStore, Cognito)
 * se sustituyen con subclases anónimas que retornan respuestas controladas,
 * sin llamadas a servicios externos.</p>
 */
class QueryHandlerTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers: stubs de colaboradores
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stub de {@link PineconeSearchClient} que retorna una lista de hits predeterminada.
     */
    private static PineconeSearchClient stubSearch(List<SearchHit> hits) {
        return new PineconeSearchClient("fake-key", "https://fake-host",
                "docs", "text", 5,
                HttpClient.newHttpClient(), Duration.ofSeconds(5)) {
            @Override
            public List<SearchHit> search(String question) {
                return hits;
            }
        };
    }

    /**
     * Stub de {@link DeepSeekClient} que retorna una respuesta de LLM predeterminada.
     */
    private static DeepSeekClient stubLlm(String answer) {
        return new DeepSeekClient("fake-key", "fake-model",
                HttpClient.newHttpClient(), Duration.ofSeconds(5)) {
            @Override
            public String chat(List<ChatMessage> messages) {
                return answer;
            }
        };
    }

    /**
     * Stub de {@link SessionStore} que no hace llamadas a DynamoDB.
     */
    private static SessionStore stubSession() {
        // Usamos el constructor publico con DynamoDbClient real pero sobrescribimos los metodos
        // que acceden a DynamoDB para que nunca hagan llamadas de red.
        return new SessionStore(DynamoDbClient.builder()
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).build(), "fake-table") {
            @Override
            public List<ChatMessage> loadSession(String sessionId) {
                return List.of();
            }
            @Override
            public void appendTurn(String sessionId, String userId,
                                   ChatMessage user, ChatMessage assistant) {
                // no-op
            }
        };
    }

    private static class RecordingSessionStore extends SessionStore {
        private String sessionId;
        private String userId;

        RecordingSessionStore() {
            super(DynamoDbClient.builder()
                    .region(software.amazon.awssdk.regions.Region.US_EAST_1).build(), "fake-table");
        }

        @Override
        public List<ChatMessage> loadSession(String sessionId) {
            return List.of();
        }

        @Override
        public void appendTurn(String sessionId, String userId,
                               ChatMessage user, ChatMessage assistant) {
            this.sessionId = sessionId;
            this.userId = userId;
        }
    }

    /**
     * Stub de {@link CognitoJwtVerifier} que acepta cualquier token no nulo
     * y retorna el sub fijo {@code "sub-usuario-test"}.
     */
    private static CognitoJwtVerifier stubValidVerifier() {
        return new CognitoJwtVerifier("us-east-1", "us-east-1_TEST", "test-client") {
            @Override
            public String verify(String bearerToken) throws UnauthorizedException {
                if (bearerToken == null || bearerToken.isBlank()) {
                    throw new UnauthorizedException("Token ausente");
                }
                return "sub-usuario-test";
            }
        };
    }

    /**
     * Stub de {@link CognitoJwtVerifier} que siempre rechaza con {@link UnauthorizedException}.
     */
    private static CognitoJwtVerifier stubRejectVerifier() {
        return new CognitoJwtVerifier("us-east-1", "us-east-1_TEST", "test-client") {
            @Override
            public String verify(String bearerToken) throws UnauthorizedException {
                throw new UnauthorizedException("Token inválido o expirado");
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests de parseRequest (método estático, sin dependencia de autenticación)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Valida que {@link QueryHandler#parseRequest} extraiga correctamente la pregunta y el identificador de sesión.
     */
    @Test
    void parseRequestReadsQuestionAndSessionId() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"question\":\"que es la atencion?\",\"session_id\":\"ses-1\"}")
                .build();

        QueryHandler.QueryRequest request = QueryHandler.parseRequest(event);

        assertEquals("que es la atencion?", request.question());
        assertEquals("ses-1", request.sessionId());
    }

    /**
     * Valida que se maneje de forma segura y sin excepciones un cuerpo HTTP nulo o evento vacío.
     */
    @Test
    void parseRequestHandlesNullBody() {
        assertNull(QueryHandler.parseRequest(null).question());
        assertNull(QueryHandler.parseRequest(APIGatewayV2HTTPEvent.builder().build()).question());
    }

    /**
     * Valida que un JSON sintácticamente inválido arroje {@link IllegalArgumentException}.
     */
    @Test
    void parseRequestRejectsInvalidJson() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("esto no es json")
                .build();

        assertThrows(IllegalArgumentException.class, () -> QueryHandler.parseRequest(event));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests de respuestas estáticas
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Valida que la respuesta exitosa incluya la respuesta del LLM, las fuentes, la sesión y el modelo.
     */
    @Test
    void buildSuccessResponseIncludesAnswerSourcesSessionAndModel() {
        APIGatewayV2HTTPResponse response = QueryHandler.buildSuccessResponse(
                "ses-9", "La atencion es clave.",
                List.of(new Source("documents/attention.pdf", "3", 0.9)),
                "deepseek/deepseek-chat-v3-0324:free");

        assertEquals(200, response.getStatusCode());
        JsonObject json = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertEquals("La atencion es clave.", json.get("answer").getAsString());
        assertEquals("ses-9", json.get("session_id").getAsString());
        assertEquals("deepseek/deepseek-chat-v3-0324:free", json.get("model").getAsString());
        assertTrue(json.getAsJsonArray("sources").size() == 1);
        assertEquals("documents/attention.pdf",
                json.getAsJsonArray("sources").get(0).getAsJsonObject().get("sourceKey").getAsString());
    }

    /**
     * Valida que la respuesta de error transporte el código de estado HTTP adecuado y el mensaje descriptivo en formato JSON.
     */
    @Test
    void buildErrorResponseCarriesStatusAndMessage() {
        APIGatewayV2HTTPResponse response = QueryHandler.buildErrorResponse(400, "Falta 'question'.");

        assertEquals(400, response.getStatusCode());
        assertEquals("Falta 'question'.", JsonParser.parseString(response.getBody())
                .getAsJsonObject().get("error").getAsString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tests de autenticación JWT en handleRequest
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Valida que una petición sin header Authorization reciba HTTP 401.
     */
    @Test
    void handleRequestReturns401WhenAuthHeaderMissing() {
        QueryHandler handler = new QueryHandler(
                stubSearch(List.of()),
                stubLlm("respuesta"),
                stubSession(),
                stubRejectVerifier());

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"question\":\"hola\"}")
                .withHeaders(Map.of("content-type", "application/json"))
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(401, response.getStatusCode());
        assertTrue(response.getBody().contains("No autenticado"));
    }

    /**
     * Valida que una petición con un token JWT válido (mock) retorna HTTP 200
     * y que el session_id en la respuesta corresponde al enviado por el cliente (sin el sub prefijado).
     */
    @Test
    void handleRequestReturns200WithValidToken() {
        SearchHit hit = new SearchHit("id1", "texto relevante", 0.9,
                "documents/paper.pdf", "0", "doc1");

        QueryHandler handler = new QueryHandler(
                stubSearch(List.of(hit)),
                stubLlm("La respuesta del LLM"),
                stubSession(),
                stubValidVerifier());

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"question\":\"que es la atencion?\",\"session_id\":\"ses-abc\"}")
                .withHeaders(Map.of("Authorization", "Bearer eyJ.fake.token"))
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
        JsonObject json = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertEquals("La respuesta del LLM", json.get("answer").getAsString());
        // El session_id expuesto al cliente es el session_id del cliente (sin el sub prefijado)
        assertEquals("ses-abc", json.get("session_id").getAsString());
    }

    @Test
    void handleRequestPersistsUserScopedSessionKeyAndUserId() {
        RecordingSessionStore sessions = new RecordingSessionStore();
        QueryHandler handler = new QueryHandler(
                stubSearch(List.of()),
                stubLlm("respuesta"),
                sessions,
                stubValidVerifier());

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"question\":\"hola\",\"session_id\":\"ses-abc\"}")
                .withHeaders(Map.of("authorization", "Bearer token"))
                .build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
        assertEquals("sub-usuario-test#ses-abc", sessions.sessionId);
        assertEquals("sub-usuario-test", sessions.userId);
    }

    @Test
    void unauthenticatedRequestDoesNotInvokeRagServices() {
        PineconeSearchClient neverCalled = new PineconeSearchClient("fake-key", "https://fake-host",
                "docs", "text", 5, HttpClient.newHttpClient(), Duration.ofSeconds(5)) {
            @Override
            public List<SearchHit> search(String question) {
                throw new AssertionError("Pinecone no debe invocarse sin autenticacion");
            }
        };
        QueryHandler handler = new QueryHandler(
                neverCalled,
                stubLlm("respuesta"),
                stubSession(),
                stubRejectVerifier());

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withBody("{\"question\":\"hola\"}")
                .withHeaders(Map.of("authorization", "Bearer invalid"))
                .build();

        assertEquals(401, handler.handleRequest(event, null).getStatusCode());
    }
}
