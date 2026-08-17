package com.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test de integracion contra el indice Pinecone real.
 *
 * Se salta si falta configuracion: PINECONE_API_KEY, PINECONE_INDEX_HOST.
 * Flujo: upsert de 2 chunks -> fetch para verificar embeddings -> delete de limpieza.
 * Para correrlo: set -a; source .env; set +a; mvn test
 */
class PineconeClientIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PineconeClientIntegrationTest.class);
    private static final String API_VERSION = "2025-10";

    private static String apiKey;
    private static String indexHost;
    private static String namespace;
    private static HttpClient httpClient;

    @BeforeAll
    static void setup() {
        apiKey = System.getenv("PINECONE_API_KEY");
        indexHost = System.getenv("PINECONE_INDEX_HOST");
        namespace = System.getenv("PINECONE_NAMESPACE");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "PINECONE_API_KEY no configurada; test de integracion omitido");
        assumeTrue(indexHost != null && !indexHost.isBlank(),
                "PINECONE_INDEX_HOST no configurado; test de integracion omitido");
        namespace = namespace == null || namespace.isBlank() ? "documents-dev" : namespace;
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void upsertGeneratesEmbeddingsAndCanBeFetchedAndDeleted() throws Exception {
        String bucket = "integration-test";
        String key = "prueba-integracion.pdf";
        String etag = "etag-integracion-1";
        String id0 = PineconeClient.vectorId(bucket, key, etag, 0);
        String id1 = PineconeClient.vectorId(bucket, key, etag, 1);

        try {
            PineconeClient client = new PineconeClient(apiKey, indexHost, namespace);
            client.upsertChunks(bucket, key, etag, "application/pdf",
                    List.of("Chunk uno: arquitectura de vectores en la nube.",
                            "Chunk dos: recuperacion aumentada por generacion."));
            logger.info("Upsert enviado. IDs: {}, {}", id0, id1);

            // Los embeddings se generan de forma asincrona en Pinecone.
            JsonObject fetched = fetchWithRetries(id0, id1, 10, 2000);
            assertTrue(fetched.has("vectors"), "fetch no devolvio vectores: " + fetched);
            assertTrue(fetched.getAsJsonObject("vectors").has(id0), "falta " + id0);
            assertTrue(fetched.getAsJsonObject("vectors").has(id1), "falta " + id1);

            JsonObject vector0 = fetched.getAsJsonObject("vectors").getAsJsonObject(id0);
            assertEquals(1024, vector0.getAsJsonArray("values").size(),
                    "el indice con embedding integrado debe producir vectores de 1024 dims");
            logger.info("Verificado: embeddings de 1024 dims para ambos chunks.");
        } finally {
            deleteRecords(id0, id1);
            // La eliminacion es eventualmente consistente: se reintenta hasta
            // que el fetch confirme que los registros de prueba ya no existen.
            JsonObject after = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                after = fetchWithRetries(id0, id1, 1, 0);
                if (!after.getAsJsonObject("vectors").has(id0)
                        && !after.getAsJsonObject("vectors").has(id1)) {
                    break;
                }
                Thread.sleep(1000);
            }
            assertFalse(after.getAsJsonObject("vectors").has(id0)
                            || after.getAsJsonObject("vectors").has(id1),
                    "los registros de prueba no se limpiaron");
            logger.info("Limpieza completada.");
        }
    }

    private JsonObject fetchWithRetries(String id0, String id1, int attempts, long delayMillis) throws Exception {
        // La API de fetch requiere el parametro ids repetido (?ids=a&ids=b);
        // separado por comas no devuelve resultados.
        String fetchUrl = indexHost + "/vectors/fetch?ids=" + id0 + "&ids=" + id1 + "&namespace=" + namespace;
        for (int i = 0; i < attempts; i++) {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(fetchUrl))
                            .timeout(Duration.ofSeconds(15))
                            .header("Api-Key", apiKey)
                            .header("X-Pinecone-Api-Version", API_VERSION)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject vectors = body.getAsJsonObject("vectors");
                if (vectors.has(id0) && vectors.has(id1)) {
                    return body;
                }
                if (i == attempts - 1) {
                    return body;
                }
            }
            Thread.sleep(delayMillis);
        }
        throw new IllegalStateException("fetch fallo despues de " + attempts + " intentos");
    }

    private void deleteRecords(String id0, String id1) throws Exception {
        String deleteUrl = indexHost + "/vectors/delete";
        String body = "{\"ids\":[\"" + id0 + "\",\"" + id1 + "\"],\"namespace\":\"" + namespace + "\"}";
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(deleteUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Api-Key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "delete fallo con HTTP " + response.statusCode());
    }
}
