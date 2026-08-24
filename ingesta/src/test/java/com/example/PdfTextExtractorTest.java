package com.example;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PdfTextExtractorTest {

    @Test
    void extractsCanonicalDocumentIdFromObjectKey() {
        String documentId = UUID.randomUUID().toString();

        assertEquals(documentId,
                PdfTextExtractor.documentIdFromKey("documents/user-123/" + documentId + "-manual.pdf"));
    }

    @Test
    void rejectsMalformedDocumentIdFromObjectKey() {
        assertNull(PdfTextExtractor.documentIdFromKey("documents/user-123/not-a-uuid-manual.pdf"));
        assertNull(PdfTextExtractor.documentIdFromKey("documents/user-123/550e8400-e29b-41d4-a716-446655440000manual.pdf"));
    }
}
