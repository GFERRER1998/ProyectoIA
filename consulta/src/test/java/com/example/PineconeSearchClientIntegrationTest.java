package com.example;

import com.example.PineconeSearchClient.SearchHit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test de integracion contra el indice Pinecone real.
 *
 * Se salta si falta configuracion: PINECONE_API_KEY, PINECONE_INDEX_HOST.
 * Flujo: busca los topK chunks mas relevantes a una pregunta y valida que la
 * respuesta incluya texto de chunk (campo "text") y metadatos de origen.
 * Para correrlo: set -a; source .env; set +a; mvn test
 */
class PineconeSearchClientIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PineconeSearchClientIntegrationTest.class);

    private static PineconeSearchClient client;

    @BeforeAll
    static void setup() {
        String apiKey = System.getenv("PINECONE_API_KEY");
        String indexHost = System.getenv("PINECONE_INDEX_HOST");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "PINECONE_API_KEY no configurada; test de integracion omitido");
        assumeTrue(indexHost != null && !indexHost.isBlank(),
                "PINECONE_INDEX_HOST no configurado; test de integracion omitido");
        String namespace = System.getenv("PINECONE_NAMESPACE");
        String textField = System.getenv("PINECONE_TEXT_FIELD");
        String topK = System.getenv("PINECONE_TOP_K");
        client = new PineconeSearchClient(apiKey, indexHost,
                namespace == null || namespace.isBlank() ? "documents-dev" : namespace,
                textField, topK == null ? 5 : Integer.parseInt(topK),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Duration.ofSeconds(30));
        logger.info("Cliente de search listo contra {}", indexHost);
    }

    @Test
    void searchReturnsRelevantChunksWithTextAndSourceMetadata() {
        // La pregunta debe relacionarse con los documentos indexados por la ingesta.
        List<SearchHit> hits = client.search("cual es el contenido de los documentos?");

        assertFalse(hits.isEmpty(), "el indice deberia contener chunks indexados");
        assertFalse(hits.size() > 5, "debe respetar el top_k configurado");

        for (SearchHit hit : hits) {
            logger.info("Hit score={} source={} chunk={} id={}", hit.score(), hit.sourceKey(),
                    hit.chunkIndex(), hit.id());
            assertNotNull(hit.id(), "todo hit debe traer _id");
            // PUNTO DE VERIFICACION: el texto de                       l chunk debe ser recuperable para RAG.
            assertNotNull(hit.text(), "el campo text no es recuperable en el search: "
                    + "si falla, el indice requiere stored=true en el field_map o "
                    + "duplicar el texto en metadata en la ingesta");
            assertFalse(hit.text().isBlank(), "el texto del chunk no debe estar vacio");
        }
    }
}
