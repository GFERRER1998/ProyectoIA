package com.example;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void normalizesPdfWhitespaceAndPageBreaks() {
        // Simula el formato irregular que normalmente entrega PDFBox.
        String result = TextChunker.normalize("  Uno   dos\r\n\f\t tres  \n\n\n cuatro ");

        assertEquals("Uno dos\n\ntres\n\ncuatro", result);
    }

    @Test
    void returnsNoChunksForEmptyOrNonPrintableText() {
        // Un documento sin texto útil no debe generar chunks vacíos.
        assertEquals("", TextChunker.normalize(" \t\n\u0000\u0001 "));
        assertTrue(TextChunker.process(" ").chunks().isEmpty());
    }

    @Test
    void createsOverlappingChunksWithoutCuttingWords() {
        // La prueba usa parámetros pequeños para poder observar varios chunks.
        String text = "uno dos tres cuatro cinco seis siete ocho nueve diez once doce";
        TextChunker.ChunkingResult result = TextChunker.process(text, 5, 1, 1);

        assertTrue(result.chunks().size() > 1);
        assertTrue(result.chunks().stream().allMatch(chunk -> chunk.length() <= 5));
        assertFalse(result.chunks().stream().anyMatch(chunk -> chunk.startsWith("os ")));
    }

    @Test
    void keepsShortTextInOneChunk() {
        // Un texto menor al límite no debe dividirse innecesariamente.
        TextChunker.ChunkingResult result = TextChunker.process("texto corto", 600, 60, 4);

        assertEquals(1, result.chunks().size());
        assertEquals("texto corto", result.chunks().get(0));
    }

    @Test
    void rejectsOverlapEqualToChunkSize() {
        // Sin espacio entre chunks no existe avance seguro del algoritmo.
        assertThrows(IllegalArgumentException.class,
                () -> TextChunker.process("texto", 10, 10, 4));
    }

    @Test
    void reportsEstimatedTokenRange() {
        // 8 caracteres / 4 caracteres por token = 2 tokens estimados.
        TextChunker.ChunkingResult result = TextChunker.process("abcdefgh", 10, 1, 4);

        assertEquals(2, result.minEstimatedTokens());
        assertEquals(2, result.maxEstimatedTokens());
    }

    @Test
    void generatesStablePineconeIdsForRetries() {
        String first = PineconeClient.vectorId("bucket", "documents/a.pdf", "etag-1", 0);
        String retry = PineconeClient.vectorId("bucket", "documents/a.pdf", "etag-1", 0);
        String nextChunk = PineconeClient.vectorId("bucket", "documents/a.pdf", "etag-1", 1);

        assertEquals(first, retry);
        assertFalse(first.equals(nextChunk));
        assertTrue(first.endsWith("_chunk_0"));
    }

    @Test
    void buildsPineconeIntegratedEmbeddingNdjsonPayload() {
        String payload = PineconeClient.buildNdjson(
                "bucket", "documents/a.pdf", "etag-1", "application/pdf", List.of("primer chunk"));

        assertTrue(payload.contains("\"_id\""));
        assertTrue(payload.contains("\"chunk_text\":\"primer chunk\""));
        assertTrue(payload.contains("\"document_id\""));
        assertTrue(payload.contains("\"source_key\":\"documents/a.pdf\""));
        assertTrue(payload.endsWith("\n"));
    }
}
