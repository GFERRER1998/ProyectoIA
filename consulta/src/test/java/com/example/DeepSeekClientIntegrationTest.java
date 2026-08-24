package com.example;

import com.example.DeepSeekClient.ChatMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Prueba de integración contra la API real de OpenRouter.
 *
 * <p>Esta prueba se ejecuta únicamente si la variable de entorno {@code OPENROUTER_API_KEY}
 * está presente en el entorno local (definida por ejemplo en un archivo {@code .env}).
 * En caso contrario, la prueba se omite dinámicamente mediante {@link org.junit.jupiter.api.Assumptions#assumeTrue}.</p>
 */
class DeepSeekClientIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClientIntegrationTest.class);

    private static DeepSeekClient client;
    private static String model;

    /**
     * Configura el cliente HTTP de OpenRouter si la clave de API está presente.
     */
    @BeforeAll
    static void setup() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "OPENROUTER_API_KEY no configurada; test de integracion omitido");
        model = System.getenv("OPENROUTER_MODEL");
        client = new DeepSeekClient(apiKey, model,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(60));
        logger.info("Cliente OpenRouter listo. Modelo: {}", model);
    }

    /**
     * Valida que una invocación real al endpoint de chat completions devuelva una respuesta no vacía del LLM.
     */
    @Test
    void chatReturnsARealAnswerFromDeepSeek() {
        String answer = client.chat(List.of(
                ChatMessage.user("Responde solo con la palabra: hola")));

        assertFalse(answer.isBlank(), "el modelo no devolvio contenido");
        logger.info("Respuesta del modelo ({}): {}", model, answer.trim());
    }
}