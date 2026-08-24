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
import java.util.ArrayList;
import java.util.List;

/**
 * Fase de recuperación (Retrieval) del flujo RAG.
 *
 * <p>Esta clase se comunica con la base de datos vectorial Pinecone para realizar búsquedas
 * semánticas por similitud textual sobre los chunks previamente indexados por el microservicio de ingesta.</p>
 *
 * <p>Al utilizar un índice con <strong>embedding integrado</strong> en Pinecone, este cliente envía la
 * pregunta en texto plano mediante el endpoint {@code POST /records/namespaces/{namespace}/search}.
 * Pinecone vectoriza la consulta del lado del servidor y calcula la similitud con los vectores almacenados,
 * retornando los fragmentos con mayor score de relevancia.</p>
 */
public final class PineconeSearchClient {

    private static final Logger logger = LoggerFactory.getLogger(PineconeSearchClient.class);
    // Versión fija del contrato REST que utiliza el índice de Pinecone.
    private static final String API_VERSION = "2025-10";
    // Tres intentos cubren errores temporales de red sin mantener la Lambda bloqueada demasiado tiempo.
    private static final int MAX_ATTEMPTS = 3;
    private static final int DEFAULT_TOP_K = 5;

    private final HttpClient httpClient;
    private final Gson gson;
    private final String apiKey;
    private final String indexHost;
    private final String namespace;
    private final String textField;
    private final int topK;
    private final Duration requestTimeout;

    /**
     * Constructor estándar de producción con valores por defecto de campo de texto y top_k.
     *
     * @param apiKey Clave de autenticación de Pinecone.
     * @param indexHost URL base del índice Pinecone (ej. {@code https://rag-index-xxx.svc.pinecone.io}).
     * @param namespace Namespace donde se encuentran los chunks indexados.
     * @throws IllegalArgumentException Si la API key o el index host son nulos o vacíos.
     */
    public PineconeSearchClient(String apiKey, String indexHost, String namespace) {
        this(apiKey, indexHost, namespace, "text", DEFAULT_TOP_K,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(30));
    }

    /**
     * Constructor con inyección total de dependencias para testing o configuración avanzada.
     *
     * @param apiKey Clave de API de Pinecone.
     * @param indexHost Host HTTPS del índice Pinecone.
     * @param namespace Namespace de búsqueda.
     * @param textField Nombre del campo del registro mapeado a embeddings (por defecto {@code text}).
     * @param topK Cantidad máxima de fragmentos relevantes a retornar.
     * @param httpClient Cliente HTTP preconfigurado.
     * @param requestTimeout Tiempo máximo de espera para la petición HTTP.
     * @throws IllegalArgumentException Si la API key o el index host son nulos o vacíos.
     */
    PineconeSearchClient(String apiKey, String indexHost, String namespace, String textField, int topK,
                         HttpClient httpClient, Duration requestTimeout) {
        if (apiKey == null || apiKey.isBlank() || indexHost == null || indexHost.isBlank()) {
            throw new IllegalArgumentException("Pinecone API key and index host are required");
        }
        this.apiKey = apiKey;
        this.indexHost = removeTrailingSlash(indexHost);
        this.namespace = namespace == null || namespace.isBlank() ? "__default__" : namespace;
        this.textField = textField == null || textField.isBlank() ? "text" : textField;
        this.topK = topK > 0 ? topK : DEFAULT_TOP_K;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.gson = new Gson();
    }

    /**
     * Fabrica una instancia de {@link PineconeSearchClient} leyendo la API key desde AWS Secrets Manager
     * y las variables de configuración desde el entorno de AWS Lambda.
     *
     * @return Nueva instancia configurada para producción.
     * @throws IllegalStateException Si faltan variables obligatorias como {@code PINECONE_SECRET_ARN} o {@code PINECONE_INDEX_HOST}.
     */
    public static PineconeSearchClient fromEnvironment() {
        String secretArn = requiredEnvironment("PINECONE_SECRET_ARN");
        String secret = SecretsManagerClient.create().getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretArn)
                .build()).secretString();
        int topK = parseTopK(System.getenv("PINECONE_TOP_K"));
        return new PineconeSearchClient(parseApiKey(secret),
                requiredEnvironment("PINECONE_INDEX_HOST"),
                System.getenv("PINECONE_NAMESPACE"),
                System.getenv("PINECONE_TEXT_FIELD"),
                topK,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(30));
    }

    /**
     * Ejecuta una búsqueda semántica en Pinecone para encontrar los fragmentos más relevantes a la pregunta.
     *
     * @param question Pregunta o consulta formulada por el usuario en texto plano.
     * @return Lista de {@link SearchHit} ordenada descendentemente por score de similitud; lista vacía si no hay coincidencias.
     * @throws IllegalArgumentException Si la pregunta es nula o vacía.
     * @throws IllegalStateException Si la petición falla tras agotar los reintentos permitidos.
     */
    public List<SearchHit> search(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question must not be blank");
        }

        // El índice tiene embedding integrado: Pinecone vectoriza el texto de la
        // pregunta server-side y compara contra los vectores de los chunks indexados.
        URI endpoint = URI.create(indexHost + "/records/namespaces/"
                + encodePathSegment(namespace) + "/search");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Api-Key", apiKey)
                .header("X-Pinecone-Api-Version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildSearchBody(question, textField, topK), StandardCharsets.UTF_8))
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // No registramos el body en logs para preservar la privacidad de los documentos y preguntas.
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    List<SearchHit> hits = parseSearchResponse(response.body());
                    logger.info("Pinecone search completado exitosamente. Hits encontrados: {}", hits.size());
                    return hits;
                }
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Pinecone search failed with HTTP status " + status);
                }
                logger.warn("Pinecone respondio HTTP {}. Reintento {}/{}", status, attempt, MAX_ATTEMPTS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Pinecone search interrupted", e);
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Pinecone search failed after retries", e);
                }
                logger.warn("Error temporal comunicando con Pinecone. Reintento {}/{}",
                        attempt, MAX_ATTEMPTS);
            }
            sleepBeforeRetry(attempt);
        }
        throw new IllegalStateException("Pinecone search failed after retries");
    }

    /**
     * Construye el cuerpo JSON para el endpoint {@code /search}.
     *
     * @param question Texto de la pregunta del usuario.
     * @param textField Nombre del campo de texto en Pinecone.
     * @param topK Cantidad máxima de resultados solicitados.
     * @return Cadena JSON con el payload de búsqueda.
     */
    static String buildSearchBody(String question, String textField, int topK) {
        JsonObject inputs = new JsonObject();
        inputs.addProperty(textField, question);
        JsonObject query = new JsonObject();
        query.add("inputs", inputs);
        query.addProperty("top_k", topK);
        JsonArray fields = new JsonArray();
        fields.add(textField);
        fields.add("source_key");
        fields.add("chunk_index");
        fields.add("document_id");
        JsonObject body = new JsonObject();
        body.add("query", query);
        body.add("fields", fields);
        return new Gson().toJson(body);
    }

    /**
     * Interpreta la respuesta JSON de búsqueda de Pinecone:
     * {@code {"result":{"hits":[{"_id", "_score", "fields": {...}}]}}}.
     *
     * @param body Cadena con la respuesta JSON de Pinecone.
     * @return Lista de objetos {@link SearchHit} extraídos.
     */
    static List<SearchHit> parseSearchResponse(String body) {
        List<SearchHit> hits = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return hits;
        }
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray array = null;
        if (root.has("result") && root.getAsJsonObject("result").has("hits")) {
            array = root.getAsJsonObject("result").getAsJsonArray("hits");
        } else if (root.has("hits")) {
            array = root.getAsJsonArray("hits");
        }
        if (array == null) {
            return hits;
        }
        for (JsonElement element : array) {
            JsonObject hit = element.getAsJsonObject();
            JsonObject fields = hit.has("fields") ? hit.getAsJsonObject("fields") : new JsonObject();
            hits.add(new SearchHit(
                    stringValue(hit, "_id"),
                    stringValue(fields, "text"),
                    hit.has("_score") ? hit.get("_score").getAsDouble() : 0.0,
                    stringValue(fields, "source_key"),
                    stringValue(fields, "chunk_index"),
                    stringValue(fields, "document_id")));
        }
        return hits;
    }

    /**
     * Extrae un valor String seguro de un objeto JSON, manejando valores nulos o ausentes.
     *
     * @param object Objeto JSON contenedor.
     * @param key Clave del campo a consultar.
     * @return Valor en String o {@code null} si no existe o es JSON null.
     */
    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * Convierte una cadena de configuración a un entero válido para top_k.
     *
     * @param value Cadena a parsear.
     * @return Entero positivo o {@link #DEFAULT_TOP_K} si es inválido.
     */
    private static int parseTopK(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_TOP_K;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : DEFAULT_TOP_K;
        } catch (NumberFormatException e) {
            return DEFAULT_TOP_K;
        }
    }

    /**
     * Parsea la clave de API desde el valor obtenido de AWS Secrets Manager.
     * Acepta texto plano o JSON con llaves {@code apiKey} o {@code PINECONE_API_KEY}.
     *
     * @param secret Contenido secreto obtenido de Secrets Manager.
     * @return API key limpia.
     * @throws IllegalArgumentException Si el secreto es nulo o vacío.
     */
    private static String parseApiKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Pinecone secret is empty");
        }
        try {
            JsonObject json = JsonParser.parseString(secret).getAsJsonObject();
            if (json.has("apiKey")) {
                return json.get("apiKey").getAsString();
            }
            if (json.has("PINECONE_API_KEY")) {
                return json.get("PINECONE_API_KEY").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Se acepta el valor en texto plano directamente.
        }
        return secret.trim();
    }

    /**
     * Determina si un código HTTP amerita reintento (429 Too Many Requests o 5xx Server Error).
     *
     * @param status Código de estado HTTP.
     * @return {@code true} si la llamada debe ser reintentada.
     */
    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    /**
     * Aplica pausa con backoff exponencial antes de reintentar.
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
     * Obtiene una variable de entorno obligatoria.
     *
     * @param name Nombre de la variable.
     * @return Valor de la variable.
     * @throws IllegalStateException Si la variable no existe o está vacía.
     */
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    /**
     * Remueve la barra inclinada final de una URL si está presente.
     *
     * @param value URL o host a normalizar.
     * @return Cadena sin la barra final.
     */
    private static String removeTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * Codifica un segmento de ruta para evitar problemas con barras en el nombre del namespace.
     *
     * @param value Segmento a codificar.
     * @return Segmento con barras reemplazadas por {@code %2F}.
     */
    private static String encodePathSegment(String value) {
        return value.replace("/", "%2F");
    }

    /**
     * Representa un fragmento de documento recuperado por la búsqueda vectorial.
     *
     * @param id Identificador único del registro en Pinecone.
     * @param text Contenido textual del chunk.
     * @param score Puntuación de similitud semántica (coseno).
     * @param sourceKey Clave o ruta original del archivo PDF en S3.
     * @param chunkIndex Posición ordinal del fragmento dentro del PDF original.
     * @param documentId Hash determinístico identificador del documento.
     */
    public record SearchHit(String id, String text, double score, String sourceKey,
                            String chunkIndex, String documentId) {
    }
}
