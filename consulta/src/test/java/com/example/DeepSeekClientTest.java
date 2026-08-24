package com.example;

import com.example.DeepSeekClient.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias para el cliente {@link DeepSeekClient}.
 * Valida la serialización de mensajes a formato compatible con OpenAI/OpenRouter y el parseo de respuestas.
 */
class DeepSeekClientTest {

    /**
     * Valida que {@link DeepSeekClient#buildRequestBody} genere correctamente la estructura JSON con modelo, roles y temperatura.
     */
    @Test
    void buildRequestBodyIncludesModelAndMessagesInOrder() {
        String body = DeepSeekClient.buildRequestBody(List.of(
                ChatMessage.system("contexto"),
                ChatMessage.user("pregunta")), "deepseek/deepseek-chat-v3-0324:free");

        assertTrue(body.contains("\"model\":\"deepseek/deepseek-chat-v3-0324:free\""));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"content\":\"contexto\""));
        assertTrue(body.contains("\"content\":\"pregunta\""));
        assertTrue(body.contains("\"temperature\":0.2"));
    }

    /**
     * Valida que {@link DeepSeekClient#parseAssistantContent} extraiga el contenido textual de la primera opción (choice).
     */
    @Test
    void parseAssistantContentReadsFirstChoiceMessage() {
        String body = """
                {"id":"chatcmpl-1","model":"deepseek/deepseek-chat-v3-0324:free",
                 "choices":[{"message":{"role":"assistant","content":"Respuesta basada en el contexto."},"finish_reason":"stop"}]}
                """;

        assertEquals("Respuesta basada en el contexto.", DeepSeekClient.parseAssistantContent(body));
    }

    /**
     * Valida que se arroje {@link IllegalStateException} con el mensaje de error cuando la respuesta no contiene elecciones.
     */
    @Test
    void parseAssistantContentThrowsWhenNoChoices() {
        String body = """
                {"error":{"message":"No auth credentials found","code":429}}
                """;

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> DeepSeekClient.parseAssistantContent(body));
        assertTrue(exception.getMessage().contains("No auth credentials found"));
    }

    /**
     * Valida que se arroje {@link IllegalStateException} cuando el contenido del mensaje devuelto es nulo.
     */
    @Test
    void parseAssistantContentThrowsWhenContentNull() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":null}}]}
                """;

        assertThrows(IllegalStateException.class, () -> DeepSeekClient.parseAssistantContent(body));
    }
}