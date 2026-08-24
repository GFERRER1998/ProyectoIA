package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Fase de generación del flujo RAG (Retrieval-Augmented Generation).
 *
 * <p>Esta clase se encarga de enviar el prompt estructurado (instrucciones del sistema,
 * contexto recuperado de la base vectorial, historial de conversación y la pregunta actual)
 * a un Modelo de Lenguaje Grande (LLM) a través de la API de OpenRouter, devolviendo
 * la respuesta generada.</p>
 *
 * <p>La API de OpenRouter expone un contrato estándar compatible con OpenAI:
 * {@code POST /chat/completions}. El modelo a utilizar se configura dinámicamente mediante
 * variables de entorno (los modelos del tier gratuito finalizan con el sufijo {@code :free}).</p>
 */
public final class DeepSeekClient {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);
    static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    static final String DEFAULT_MODEL = "deepseek/deepseek-chat-v3-0324:free";
    // Tres intentos cubren errores temporales de red o saturación sin bloquear Lambda en exceso.
    private static final int MAX_ATTEMPTS = 3;
    // Temperatura baja para respuestas RAG más deterministas y estrictamente apegadas al contexto.
    private static final double TEMPERATURE = 0.2;

    private final HttpClient httpClient;
    private final Gson gson;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration requestTimeout;

    /**
     * Constructor para uso general con el modelo y timeouts por defecto.
     *
     * @param apiKey Clave de API para autenticarse contra OpenRouter.
     * @throws IllegalArgumentException Si la clave de API es nula o vacía.
     */
    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(60));
    }

    /**
     * Constructor con inyección de dependencias para testing o configuración avanzada.
     *
     * @param apiKey Clave de API de OpenRouter.
     * @param model Identificador del modelo (ej. {@code deepseek/deepseek-chat-v3-0324:free}).
     * @param httpClient Cliente HTTP preconfigurado (reutilizable en warm starts de Lambda).
     * @param requestTimeout Tiempo máximo de espera para la respuesta HTTP.
     * @throws IllegalArgumentException Si la clave de API es nula o vacía.
     */
    DeepSeekClient(String apiKey, String model, HttpClient httpClient, Duration requestTimeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenRouter API key is required");
        }
        this.apiKey = apiKey;
        this.baseUrl = DEFAULT_BASE_URL;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.gson = new Gson();
    }

    /**
     * Fabrica una instancia de {@link DeepSeekClient} leyendo las credenciales desde AWS Secrets Manager
     * y los parámetros de configuración desde las variables de entorno de AWS Lambda.
     *
     * @return Nueva instancia configurada para producción.
     * @throws IllegalStateException Si la variable {@code OPENROUTER_SECRET_ARN} no está definida.
     */
    public static DeepSeekClient fromEnvironment() {
        String secretArn = requiredEnvironment("OPENROUTER_SECRET_ARN");
        String secret = SecretsManagerClient.create().getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretArn)
                .build()).secretString();
        return new DeepSeekClient(parseApiKey(secret),
                System.getenv("OPENROUTER_MODEL"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(60));
    }

    /**
     * Obtiene el identificador del modelo actualmente configurado en el cliente.
     *
     * @return Nombre/ID del modelo en OpenRouter (ej. {@code deepseek/deepseek-chat-v3-0324:free}).
     */
    public String model() {
        return model;
    }

    /**
     * Envía la lista de mensajes al endpoint {@code /chat/completions} de OpenRouter y
     * devuelve el contenido textual de la respuesta generada por el asistente.
     *
     * <p>Aplica una estrategia de reintentos con retroceso exponencial ante errores temporales
     * (HTTP 429 por límite de tasa o errores HTTP 5xx del servidor).</p>
     *
     * @param messages Lista cronológica de mensajes (system prompt, historial y user prompt).
     * @return Texto de la respuesta generado por el LLM.
     * @throws IllegalArgumentException Si la lista de mensajes es nula o vacía.
     * @throws IllegalStateException Si la petición falla tras agotar los reintentos o por error no recuperable.
     */
    public String chat(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages must not be empty");
        }

        URI endpoint = URI.create(baseUrl + "/chat/completions");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-Title", "consulta-rag-lambda")
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildRequestBody(messages, model), StandardCharsets.UTF_8))
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // No registramos el cuerpo del request en logs para proteger la privacidad del usuario.
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    String content = parseAssistantContent(response.body());
                    logger.info("OpenRouter completado exitosamente. Longitud de respuesta: {} caracteres", content.length());
                    return content;
                }

                // El error 429 en modelos free es común (~20 req/min, ~50 req/día): se informa con claridad.
                if (status == 429 && attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("OpenRouter rate limit (HTTP 429): "
                            + "el tier free permite ~20 req/min y 50 req/dia. Error: "
                            + extractErrorMessage(response.body()));
                }
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("OpenRouter chat failed with HTTP status " + status
                            + ": " + extractErrorMessage(response.body()));
                }
                logger.warn("OpenRouter respondio HTTP {}. Reintento {}/{}", status, attempt, MAX_ATTEMPTS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OpenRouter request interrupted", e);
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("OpenRouter request failed after retries", e);
                }
                logger.warn("Error temporal comunicando con OpenRouter. Reintento {}/{}",
                        attempt, MAX_ATTEMPTS);
            }
            sleepBeforeRetry(attempt);
        }
        throw new IllegalStateException("OpenRouter request failed after retries");
    }

    /**
     * Construye el cuerpo JSON para la petición {@code /chat/completions}.
     *
     * @param messages Lista de mensajes a serializar.
     * @param model Identificador del modelo objetivo.
     * @return Cadena JSON con el payload de la petición.
     */
    static String buildRequestBody(List<ChatMessage> messages, String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray array = new JsonArray();
        for (ChatMessage message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        body.add("messages", array);
        body.addProperty("temperature", TEMPERATURE);
        return new Gson().toJson(body);
    }

    /**
     * Extrae el contenido del mensaje del asistente desde la respuesta JSON:
     * {@code choices[0].message.content}.
     *
     * @param body Cuerpo de la respuesta JSON recibida de OpenRouter.
     * @return Cadena con el texto de la respuesta.
     * @throws IllegalStateException Si la respuesta no contiene elecciones o el contenido es nulo.
     */
    static String parseAssistantContent(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("OpenRouter response without choices: " + extractErrorMessage(body));
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            throw new IllegalStateException("OpenRouter response without message content");
        }
        return content.getAsString();
    }

    /**
     * Extrae el mensaje de error legible del cuerpo de respuesta cuando el status HTTP no es exitoso.
     *
     * @param body Cuerpo de respuesta recibido de la API.
     * @return Descripción del error o resumen acotado.
     */
    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "sin detalle";
        }
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            if (error != null && error.has("message")) {
                return error.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Si no es un JSON válido, devolvemos el texto plano limitado a 200 caracteres.
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    /**
     * Parsea la API Key desde el valor obtenido de AWS Secrets Manager.
     * Soporta tanto texto plano como objetos JSON (ej. {@code {"apiKey":"..."}} o {@code {"OPENROUTER_API_KEY":"..."}}).
     *
     * @param secret Contenido secreto obtenido de Secrets Manager.
     * @return Clave de API limpia.
     * @throws IllegalArgumentException Si el secreto es nulo o vacío.
     */
    private static String parseApiKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("OpenRouter secret is empty");
        }
        try {
            JsonObject json = JsonParser.parseString(secret).getAsJsonObject();
            if (json.has("apiKey")) {
                return json.get("apiKey").getAsString();
            }
            if (json.has("OPENROUTER_API_KEY")) {
                return json.get("OPENROUTER_API_KEY").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Se acepta el valor en texto plano directamente.
        }
        return secret.trim();
    }

    /**
     * Determina si un código de estado HTTP es susceptible de reintento (429 Too Many Requests o 5xx Server Error).
     *
     * @param status Código de estado HTTP.
     * @return {@code true} si se debe reintentar la llamada.
     */
    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    /**
     * Aplica una pausa con backoff exponencial antes del siguiente reintento:
     * 250 ms en el intento 1, 500 ms en el intento 2 y 1000 ms en el intento 3.
     *
     * @param attempt Número de intento actual (1-indexed).
     * @throws IllegalStateException Si el hilo es interrumpido durante la espera.
     */
    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(250L * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry interrupted", e);
        }
    }

    /**
     * Obtiene una variable de entorno obligatoria del sistema.
     *
     * @param name Nombre de la variable de entorno.
     * @return Valor de la variable.
     * @throws IllegalStateException Si la variable no existe o está en blanco.
     */
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    /**
     * Representa un turno o mensaje dentro de la conversación del LLM.
     *
     * @param role Rol del emisor del mensaje ({@code system}, {@code user} o {@code assistant}).
     * @param content Contenido textual del mensaje.
     */
    public record ChatMessage(String role, String content) {
        /** Crea un mensaje de rol "system". */
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        /** Crea un mensaje de rol "user". */
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        /** Crea un mensaje de rol "assistant". */
        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }
}