package com.example;

import org.junit.jupiter.api.Test;

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
}
