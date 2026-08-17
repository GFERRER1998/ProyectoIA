package com.example;

import com.google.gson.Gson;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Fase de persistencia: envia los chunks normalizados a Pinecone.
 *
 * Pinecone recibe el texto mediante un indice con embedding integrado y
 * genera el vector internamente. Esta clase no sabe como se extrae ni como
 * se divide el PDF; solo recibe chunks listos y los convierte en registros.
 */
public final class PineconeClient {

    private static final Logger logger = LoggerFactory.getLogger(PineconeClient.class);
    // Version fija del contrato REST que utiliza el indice de Pinecone.
    private static final String API_VERSION = "2025-10";
    // Enviamos lotes pequenos para controlar memoria, latencia y reintentos.
    private static final int MAX_RECORDS_PER_REQUEST = 100;
    // Tres intentos cubren errores temporales sin mantener la Lambda bloqueada demasiado tiempo.
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient httpClient;
    private final Gson gson;
    private final String apiKey;
    private final String indexHost;
    private final String namespace;
    private final Duration requestTimeout;

    public PineconeClient(String apiKey, String indexHost, String namespace) {
        // Constructor usado por la Lambda en produccion.
        // HttpClient se reutiliza durante invocaciones calientes de Lambda.
        this(apiKey, indexHost, namespace,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(30));
    }

    PineconeClient(String apiKey, String indexHost, String namespace,
                   HttpClient httpClient, Duration requestTimeout) {
        // Validamos la configuracion antes de aceptar documentos para evitar
        // llamadas HTTP imposibles o errores poco claros durante el upsert.
        if (apiKey == null || apiKey.isBlank() || indexHost == null || indexHost.isBlank()) {
            throw new IllegalArgumentException("Pinecone API key and index host are required");
        }
        this.apiKey = apiKey;
        // El host puede venir con una barra final; la quitamos para no formar URLs //records.
        this.indexHost = removeTrailingSlash(indexHost);
        this.namespace = namespace == null || namespace.isBlank() ? "__default__" : namespace;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.gson = new Gson();
    }

    /** Loads the API key from Secrets Manager and the endpoint from Lambda environment variables. */
    public static PineconeClient fromEnvironment() {
        // La API key nunca se lee directamente del codigo ni se registra en logs.
        // Lambda solo recibe el ARN del secreto y lo consulta con IAM.
        String secretArn = requiredEnvironment("PINECONE_SECRET_ARN");
        String secret = SecretsManagerClient.create().getSecretValue(GetSecretValueRequest.builder()
                .secretId(secretArn)
                .build()).secretString();
        return new PineconeClient(parseApiKey(secret),
                requiredEnvironment("PINECONE_INDEX_HOST"),
                System.getenv("PINECONE_NAMESPACE"));
    }

    public void upsertChunks(String bucket, String key, String etag, String contentType,
                             List<String> chunks) {
        // Un documento puede producir muchos chunks. Todos comparten un documentId
        // y se diferencian por el indice del chunk.
        String documentId = documentId(bucket, key, etag);
        List<PineconeRecord> records = new ArrayList<>();
        String ingestedAt = Instant.now().toString();

        for (int index = 0; index < chunks.size(); index++) {
            String chunk = chunks.get(index);
            // El mismo bucket + key + etag + index siempre produce el mismo ID.
            // Por eso un reintento sobrescribe, en vez de duplicar, el registro.
            records.add(new PineconeRecord(
                    vectorId(bucket, key, etag, index),
                    chunk,
                    Map.of(
                            "document_id", documentId,
                            "source_bucket", bucket,
                            "source_key", key,
                            "chunk_index", Integer.toString(index),
                            "chunk_text", chunk,
                            "content_type", contentType == null ? "application/pdf" : contentType,
                            "ingested_at", ingestedAt)));
        }

        for (int start = 0; start < records.size(); start += MAX_RECORDS_PER_REQUEST) {
            // Pinecone recibe varios lotes si el PDF tiene mas de 100 chunks.
            int end = Math.min(start + MAX_RECORDS_PER_REQUEST, records.size());
            sendBatch(records.subList(start, end));
        }
    }

    private void sendBatch(List<PineconeRecord> records) {
        // Para un indice con embedding integrado, Pinecone espera NDJSON:
        // un objeto JSON por linea, con _id y el campo configurado en field_map.
        // No usamos /vectors/upsert ni enviamos valores numericos desde Lambda.
        StringBuilder body = new StringBuilder();
        for (PineconeRecord record : records) {
            JsonObject json = new JsonObject();
            json.addProperty("_id", record.id());
            json.addProperty("chunk_text", record.chunkText());
            record.metadata().forEach(json::addProperty);
            body.append(gson.toJson(json)).append('\n');
        }

        // El namespace forma parte de la ruta del endpoint de registros.
        URI endpoint = URI.create(indexHost + "/records/namespaces/"
                + encodePathSegment(namespace) + "/upsert");
        // Los headers identifican la API, autentican la llamada y declaran NDJSON.
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-ndjson")
                .header("Api-Key", apiKey)
                .header("X-Pinecone-Api-Version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // No registramos request.body: contiene el texto del documento.
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    // Pinecone responde con exito; el contenido de respuesta no
                    // es necesario para continuar la ingesta.
                    logger.info("Pinecone upsert completado. Registros: {}", records.size());
                    return;
                }

                // 429 y 5xx suelen ser temporales. Los demas errores indican
                // configuracion o payload invalido y se propagan inmediatamente.
                if (!isRetryable(status) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Pinecone upsert failed with HTTP status " + status);
                }
                logger.warn("Pinecone respondió HTTP {}. Reintento {}/{}", status, attempt, MAX_ATTEMPTS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Pinecone request interrupted", e);
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException("Pinecone request failed after retries", e);
                }
                logger.warn("Error temporal comunicando con Pinecone. Reintento {}/{}",
                        attempt, MAX_ATTEMPTS);
            }
            sleepBeforeRetry(attempt);
        }
    }

    static String documentId(String bucket, String key, String etag) {
        // El separador evita ambiguedades entre combinaciones de valores.
        return sha256(bucket + "\0" + key + "\0" + safe(etag));
    }

    static String vectorId(String bucket, String key, String etag, int chunkIndex) {
        // Usamos 16 caracteres para mantener IDs cortos, pero suficientemente unicos.
        return documentId(bucket, key, etag).substring(0, 16) + "_chunk_" + chunkIndex;
    }

    static String buildNdjson(String bucket, String key, String etag, String contentType,
                              List<String> chunks) {
        // Metodo auxiliar para tests y diagnostico: construye el mismo formato
        // que se envia al endpoint sin ejecutar una llamada de red.
        String documentId = documentId(bucket, key, etag);
        String ingestedAt = Instant.now().toString();
        Gson gson = new Gson();
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < chunks.size(); index++) {
            JsonObject json = new JsonObject();
            json.addProperty("_id", vectorId(bucket, key, etag, index));
            json.addProperty("document_id", documentId);
            json.addProperty("source_bucket", bucket);
            json.addProperty("source_key", key);
            json.addProperty("chunk_index", Integer.toString(index));
            json.addProperty("chunk_text", chunks.get(index));
            json.addProperty("content_type", contentType == null ? "application/pdf" : contentType);
            json.addProperty("ingested_at", ingestedAt);
            body.append(gson.toJson(json)).append('\n');
        }
        return body.toString();
    }

    private static String parseApiKey(String secret) {
        // Aceptamos tanto un secreto plano como JSON:
        // "mi-key" o {"apiKey":"mi-key"} / {"PINECONE_API_KEY":"mi-key"}.
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
            // También aceptamos un secreto cuyo contenido completo sea la API key.
        }
        return secret.trim();
    }

    private static String sha256(String value) {
        // SHA-256 convierte la identidad del documento en un ID estable sin
        // exponer bucket, key o etag dentro del identificador.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    private static void sleepBeforeRetry(int attempt) {
        // Backoff: 250 ms, 500 ms y 1000 ms entre intentos.
        try {
            Thread.sleep(250L * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry interrupted", e);
        }
    }

    private static String requiredEnvironment(String name) {
        // Las variables obligatorias faltantes deben fallar durante la inicializacion.
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static String removeTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encodePathSegment(String value) {
        // Permite namespaces con subrutas sin romper el endpoint HTTP.
        return value.replace("/", "%2F");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record PineconeRecord(String id, String chunkText, Map<String, String> metadata) {
    }
}
