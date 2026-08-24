package com.example;

import com.example.DeepSeekClient.ChatMessage;
import com.example.PineconeSearchClient.SearchHit;
import com.example.RagContextBuilder.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias para el constructor de contexto RAG {@link RagContextBuilder}.
 * Valida la correcta inyección de fragmentos numerados, la preservación del historial conversacional y la deduplicación de fuentes.
 */
class RagContextBuilderTest {

    private static final List<SearchHit> HITS = List.of(
            new SearchHit("id0", "Los transformers usan atencion.", 0.9,
                    "documents/attention.pdf", "3", "doc1"),
            new SearchHit("id1", "La atencion permite contexto global.", 0.8,
                    "documents/attention.pdf", "4", "doc1"));

    /**
     * Valida que {@link RagContextBuilder#buildMessages} ensamble el mensaje del sistema con las citas correspondientes
     * y finalice con el mensaje del usuario.
     */
    @Test
    void buildMessagesStartsWithSystemPromptWithContextAndEndsWithQuestion() {
        List<ChatMessage> messages = RagContextBuilder.buildMessages(
                "que es la atencion?", HITS, List.of());

        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(0).content().contains("[Fuente 1] documents/attention.pdf (chunk 3)"));
        assertTrue(messages.get(0).content().contains("Los transformers usan atencion."));
        assertTrue(messages.get(0).content().contains("[Fuente 2] documents/attention.pdf (chunk 4)"));
        assertEquals("user", messages.get(1).role());
        assertEquals("que es la atencion?", messages.get(1).content());
    }

    /**
     * Valida que el historial de turnos previos se inserte cronológicamente entre el system prompt y la pregunta actual.
     */
    @Test
    void buildMessagesAppendsHistoryBetweenSystemAndQuestion() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("pregunta anterior"),
                ChatMessage.assistant("respuesta anterior"));

        List<ChatMessage> messages = RagContextBuilder.buildMessages("pregunta actual", HITS, history);

        assertEquals(4, messages.size());
        assertEquals("user", messages.get(1).role());
        assertEquals("pregunta anterior", messages.get(1).content());
        assertEquals("assistant", messages.get(2).role());
        assertEquals("pregunta actual", messages.get(3).content());
    }

    /**
     * Valida que si no se encontraron fragmentos relevantes, el bloque de contexto indique un mensaje de advertencia.
     */
    @Test
    void buildContextBlockWithoutHitsSaysNoDocuments() {
        String block = RagContextBuilder.buildContextBlock(List.of());

        assertTrue(block.contains("No se encontraron documentos relevantes"));
    }

    /**
     * Valida que {@link RagContextBuilder#buildSources} elimine duplicados del mismo archivo y chunk, ignorando hits con origen nulo.
     */
    @Test
    void buildSourcesDeduplicatesBySourceAndChunkPreservingOrder() {
        List<SearchHit> hits = List.of(
                new SearchHit("a", "t1", 0.9, "documents/x.pdf", "1", "d"),
                new SearchHit("b", "t2", 0.8, "documents/x.pdf", "1", "d"),
                new SearchHit("c", "t3", 0.7, "documents/y.pdf", "2", "e"),
                new SearchHit("d", "t4", 0.6, null, "5", "f"));

        List<Source> sources = RagContextBuilder.buildSources(hits);

        assertEquals(2, sources.size());
        assertEquals("documents/x.pdf", sources.get(0).sourceKey());
        assertEquals("1", sources.get(0).chunkIndex());
        assertEquals(0.9, sources.get(0).score());
        assertEquals("documents/y.pdf", sources.get(1).sourceKey());
        assertFalse(sources.stream().anyMatch(s -> s.sourceKey() == null));
    }
}