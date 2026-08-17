package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Segunda etapa del procesamiento del PDF.
 *
 * Recibe el texto plano producido por PDFTextStripper, elimina el ruido típico
 * de PDF y lo divide en fragmentos que luego podrán enviarse a embeddings.
 */
public final class TextChunker {

    // Valores utilizados si Lambda no recibe las variables de entorno opcionales.
    private static final int DEFAULT_CHUNK_TOKENS = 600;
    private static final int DEFAULT_OVERLAP_TOKENS = 60;
    private static final int DEFAULT_CHARS_PER_TOKEN = 4;
    private static final int MAX_CHUNKS = 10_000;

    private TextChunker() {
    }

    public static ChunkingResult process(String text) {
        // Lambda puede modificar estos valores sin recompilar el JAR.
        int chunkTokens = readPositiveInt("CHUNK_SIZE", DEFAULT_CHUNK_TOKENS);
        int overlapTokens = readNonNegativeInt("CHUNK_OVERLAP", DEFAULT_OVERLAP_TOKENS);
        int charsPerToken = readPositiveInt("CHARS_PER_TOKEN", DEFAULT_CHARS_PER_TOKEN);
        return process(text, chunkTokens, overlapTokens, charsPerToken);
    }

    static ChunkingResult process(String text, int chunkTokens, int overlapTokens, int charsPerToken) {
        // Validamos la configuración antes de procesar documentos reales.
        // El overlap debe ser menor que el tamaño del chunk para garantizar progreso.
        if (chunkTokens <= 0 || overlapTokens < 0 || charsPerToken <= 0) {
            throw new IllegalArgumentException("Chunking parameters must be positive and overlap cannot be negative");
        }
        if (overlapTokens >= chunkTokens) {
            throw new IllegalArgumentException("CHUNK_OVERLAP must be smaller than CHUNK_SIZE");
        }

        // Paso 1: limpiamos el texto antes de calcular tamaños.
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return new ChunkingResult(normalized, List.of(), chunkTokens, overlapTokens, charsPerToken);
        }

        // No contamos tokens reales porque todavía no usamos un tokenizer externo.
        // Usamos la aproximación configurable: 1 token ~= 4 caracteres.
        int targetChars = Math.max(1, chunkTokens * charsPerToken);
        int overlapChars = overlapTokens * charsPerToken;
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < normalized.length()) {
            if (chunks.size() >= MAX_CHUNKS) {
                throw new IllegalArgumentException("Text exceeds the maximum supported number of chunks");
            }

            // Calculamos el final aproximado del chunk.
            int end = Math.min(start + targetChars, normalized.length());
            if (end < normalized.length()) {
                // Retrocedemos al último whitespace para no cortar una palabra.
                int boundary = lastWhitespaceBefore(normalized, end);
                if (boundary > start) {
                    end = boundary;
                }
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (end >= normalized.length()) {
                break;
            }

            // El siguiente chunk comienza antes del final anterior para conservar
            // contexto entre fragmentos consecutivos.
            int nextStart = Math.max(start + 1, end - overlapChars);
            // Si el overlap cae dentro de una palabra, avanzamos hasta su final.
            while (nextStart < normalized.length() && !Character.isWhitespace(normalized.charAt(nextStart))) {
                nextStart++;
            }
            while (nextStart < normalized.length() && Character.isWhitespace(normalized.charAt(nextStart))) {
                nextStart++;
            }
            start = nextStart;
        }

        return new ChunkingResult(normalized, List.copyOf(chunks), chunkTokens, overlapTokens, charsPerToken);
    }

    private static int lastWhitespaceBefore(String text, int endExclusive) {
        for (int index = endExclusive - 1; index > 0; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    /** Keeps paragraph boundaries while removing PDF-specific and non-printable noise. */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        // PDFBox puede devolver saltos de página, retornos de carro, tabs y
        // caracteres de control. Los convertimos a un formato de texto estable.
        String normalized = text
                .replace('\u000c', '\n')
                .replace('\r', '\n')
                .replaceAll("[^\\p{Print}\\n\\t]", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                // Conservamos como máximo una línea vacía entre párrafos.
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        return normalized;
    }

    private static int readPositiveInt(String name, int defaultValue) {
        // Un valor inválido no debe tumbar la invocación: usamos el default seguro.
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int readNonNegativeInt(String name, int defaultValue) {
        // El overlap puede ser cero, pero nunca negativo.
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public record ChunkingResult(
            String normalizedText,
            List<String> chunks,
            int chunkTokens,
            int overlapTokens,
            int charsPerToken) {

        public int estimatedTokens(String chunk) {
            // Es una estimación para observabilidad, no un conteo exacto del modelo.
            return (int) Math.ceil((double) chunk.length() / charsPerToken);
        }

        public int minEstimatedTokens() {
            return chunks.stream().mapToInt(this::estimatedTokens).min().orElse(0);
        }

        public int maxEstimatedTokens() {
            return chunks.stream().mapToInt(this::estimatedTokens).max().orElse(0);
        }
    }
}
