package com.example;

import com.example.DeepSeekClient.ChatMessage;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias para la capa de persistencia {@link SessionStore}.
 * Valida la conversión bidireccional entre {@link ChatMessage} y {@link AttributeValue} de DynamoDB,
 * la resiliencia ante datos malformados y la política de recorte de turnos (FIFO).
 */
class SessionStoreTest {

    /**
     * Valida que la serialización y deserialización a través de atributos de DynamoDB preserve roles y contenido intactos.
     */
    @Test
    void toAndFromMessagesAttributeRoundTrip() {
        List<ChatMessage> original = List.of(
                ChatMessage.user("que es un transformer?"),
                ChatMessage.assistant("una arquitectura de atencion."));

        AttributeValue attribute = SessionStore.toMessagesAttribute(original);
        List<ChatMessage> parsed = SessionStore.fromMessagesAttribute(attribute.l());

        assertEquals(2, parsed.size());
        assertEquals("user", parsed.get(0).role());
        assertEquals("que es un transformer?", parsed.get(0).content());
        assertEquals("assistant", parsed.get(1).role());
        assertEquals("una arquitectura de atencion.", parsed.get(1).content());
    }

    /**
     * Valida que el parser ignore de forma segura entradas que no sean mapas o que no contengan los atributos esperados.
     */
    @Test
    void fromMessagesAttributeSkipsMalformedEntries() {
        List<AttributeValue> values = List.of(
                AttributeValue.builder()
                        .m(java.util.Map.of("role", AttributeValue.builder().s("user").build(),
                                "content", AttributeValue.builder().s("valido").build()))
                        .build(),
                AttributeValue.builder().s("no es un mapa").build());

        List<ChatMessage> parsed = SessionStore.fromMessagesAttribute(values);

        assertEquals(1, parsed.size());
        assertEquals("valido", parsed.get(0).content());
    }

    /**
     * Valida que {@link SessionStore#trimHistory} mantenga únicamente los últimos N turnos solicitados (2 mensajes por turno).
     */
    @Test
    void trimHistoryKeepsOnlyLastTurns() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("p1"), ChatMessage.assistant("r1"),
                ChatMessage.user("p2"), ChatMessage.assistant("r2"),
                ChatMessage.user("p3"), ChatMessage.assistant("r3"),
                ChatMessage.user("p4"), ChatMessage.assistant("r4"));

        List<ChatMessage> trimmed = SessionStore.trimHistory(history, 2);

        assertEquals(4, trimmed.size());
        assertEquals("p3", trimmed.get(0).content());
        assertEquals("r4", trimmed.get(3).content());
    }

    /**
     * Valida que cuando la cantidad de turnos almacenados es menor al límite configurado, se conserve la lista completa.
     */
    @Test
    void trimHistoryKeepsAllWhenUnderLimit() {
        List<ChatMessage> history = List.of(ChatMessage.user("p1"), ChatMessage.assistant("r1"));

        assertTrue(SessionStore.trimHistory(history, 6).size() == 2);
    }
}