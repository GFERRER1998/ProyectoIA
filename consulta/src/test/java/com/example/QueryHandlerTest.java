package com.example;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.example.RagContextBuilder.Source;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias para el controlador de consultas {@link QueryHandler}.
 * Valida el parseo del payload HTTP v2 y la construcción de respuestas exitosas y de error.
 */
class QueryHandlerTest {

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
}