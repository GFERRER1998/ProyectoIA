package com.example;

import com.example.PineconeSearchClient.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias para el cliente de búsqueda {@link PineconeSearchClient}.
 * Valida la construcción de solicitudes de búsqueda y la deserialización de respuestas JSON de Pinecone.
 */
class PineconeSearchClientTest {

    /**
     * Valida que {@link PineconeSearchClient#buildSearchBody} incluya el campo de texto, el parámetro top_k y los campos solicitados.
     */
    @Test
    void buildSearchBodyIncludesTextFieldTopKAndRequestedFields() {
        String body = PineconeSearchClient.buildSearchBody("cual es la politica?", "text", 5);

        assertTrue(body.contains("\"text\":\"cual es la politica?\""));
        assertTrue(body.contains("\"top_k\":5"));
        assertTrue(body.contains("\"source_key\""));
        assertTrue(body.contains("\"chunk_index\""));
        assertTrue(body.contains("\"document_id\""));
    }

    /**
     * Valida que {@link PineconeSearchClient#parseSearchResponse} lea correctamente la lista de hits anidada en {@code result.hits}.
     */
    @Test
    void parseSearchResponseReadsHitsFromResultWrapper() {
        String body = """
                {"result":{"hits":[
                  {"_id":"abc_chunk_0","_score":0.92,
                   "fields":{"text":"texto del primer chunk","source_key":"doc1.pdf","chunk_index":"0","document_id":"abc"}},
                  {"_id":"abc_chunk_3","_score":0.81,
                   "fields":{"text":"texto del cuarto chunk","source_key":"doc1.pdf","chunk_index":"3","document_id":"abc"}}
                ]}}
                """;

        List<SearchHit> hits = PineconeSearchClient.parseSearchResponse(body);

        assertEquals(2, hits.size());
        SearchHit first = hits.get(0);
        assertEquals("abc_chunk_0", first.id());
        assertEquals(0.92, first.score());
        assertEquals("texto del primer chunk", first.text());
        assertEquals("doc1.pdf", first.sourceKey());
        assertEquals("0", first.chunkIndex());
        assertEquals("abc", first.documentId());
        assertEquals("texto del cuarto chunk", hits.get(1).text());
    }

    /**
     * Valida que el parser acepte también respuestas con el arreglo de {@code hits} en la raíz del JSON.
     */
    @Test
    void parseSearchResponseAcceptsTopLevelHits() {
        String body = """
                {"hits":[{"_id":"x_chunk_1","_score":0.5,
                  "fields":{"text":"un chunk","source_key":"a.pdf","chunk_index":"1"}}]}
                """;

        List<SearchHit> hits = PineconeSearchClient.parseSearchResponse(body);

        assertEquals(1, hits.size());
        assertEquals("x_chunk_1", hits.get(0).id());
    }

    /**
     * Valida que no ocurran excepciones si los campos de metadatos no están presentes en el hit.
     */
    @Test
    void parseSearchResponseHandlesMissingFieldsGracefully() {
        String body = """
                {"result":{"hits":[{"_id":"sin-campos","_score":0.4}]}}
                """;

        List<SearchHit> hits = PineconeSearchClient.parseSearchResponse(body);

        assertEquals(1, hits.size());
        assertNull(hits.get(0).text());
        assertNull(hits.get(0).sourceKey());
    }

    @Test
    void parseSearchResponseUsesConfiguredTextField() {
        String body = "{\"hits\":[{\"_id\":\"custom-1\",\"fields\":{\"content\":\"texto configurable\"}}]}";

        List<SearchHit> hits = PineconeSearchClient.parseSearchResponse(body, "content");

        assertEquals("texto configurable", hits.get(0).text());
    }

    /**
     * Valida que se devuelva una lista vacía para cadenas en blanco o respuestas sin resultados.
     */
    @Test
    void parseSearchResponseReturnsEmptyForBlankOrEmptyResult() {
        assertTrue(PineconeSearchClient.parseSearchResponse("").isEmpty());
        assertTrue(PineconeSearchClient.parseSearchResponse("{\"result\":{\"hits\":[]}}").isEmpty());
        assertTrue(PineconeSearchClient.parseSearchResponse("{\"error\":{\"message\":\"boom\"}}").isEmpty());
    }
}
