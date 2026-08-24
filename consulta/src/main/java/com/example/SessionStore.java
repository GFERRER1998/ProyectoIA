package com.example;

import com.example.DeepSeekClient.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Capa de persistencia y gestión de sesiones de chat en Amazon DynamoDB.
 *
 * <p>Cada sesión de usuario se almacena en una fila individual identificada por la clave primaria
 * {@code session_id} (String). Los turnos de conversación (pares pregunta-respuesta) se guardan
 * como una lista ordenada de mapas con roles, contenidos y marcas de tiempo UTC.</p>
 *
 * <p>Para evitar un crecimiento ilimitado que sature la ventana de contexto del LLM y aumente costos,
 * el historial se recorta automáticamente a los últimos <em>N</em> turnos más recientes (FIFO).</p>
 */
public final class SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionStore.class);
    // Cantidad por defecto de turnos (pares usuario-asistente) que se conservan en memoria.
    private static final int DEFAULT_HISTORY_TURNS = 6;

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final int historyTurns;

    /**
     * Constructor estándar con la cantidad de turnos por defecto.
     *
     * @param dynamoDb Cliente SDK de DynamoDB.
     * @param tableName Nombre de la tabla de sesiones en DynamoDB.
     * @throws IllegalArgumentException Si el cliente o el nombre de tabla son nulos o vacíos.
     */
    public SessionStore(DynamoDbClient dynamoDb, String tableName) {
        this(dynamoDb, tableName, DEFAULT_HISTORY_TURNS);
    }

    /**
     * Constructor con configuración explícita de límite de turnos.
     *
     * @param dynamoDb Cliente SDK de DynamoDB.
     * @param tableName Nombre de la tabla de DynamoDB.
     * @param historyTurns Cantidad de turnos a conservar en memoria.
     * @throws IllegalArgumentException Si el cliente o el nombre de tabla son nulos o vacíos.
     */
    SessionStore(DynamoDbClient dynamoDb, String tableName, int historyTurns) {
        if (dynamoDb == null || tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("DynamoDb client and table name are required");
        }
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.historyTurns = historyTurns > 0 ? historyTurns : DEFAULT_HISTORY_TURNS;
    }

    /**
     * Crea una instancia de {@link SessionStore} inicializada con las variables de entorno de AWS Lambda.
     *
     * @return Nueva instancia configurada para producción.
     * @throws IllegalStateException Si la variable {@code SESSIONS_TABLE} no está configurada.
     */
    public static SessionStore fromEnvironment() {
        return new SessionStore(DynamoDbClient.builder().build(),
                requiredEnvironment("SESSIONS_TABLE"),
                parseHistoryTurns(System.getenv("HISTORY_TURNS")));
    }

    /**
     * Carga el historial de conversación de una sesión en orden cronológico,
     * recortado a los últimos <em>N</em> turnos permitidos.
     *
     * @param sessionId Identificador único de la sesión.
     * @return Lista de {@link ChatMessage} de la sesión; lista vacía si la sesión no existe aún.
     */
    public List<ChatMessage> loadSession(String sessionId) {
        Map<String, AttributeValue> item = getItem(sessionId);
        if (item == null) {
            return List.of();
        }
        AttributeValue messages = item.get("messages");
        if (messages == null || !messages.hasL()) {
            return List.of();
        }
        return trimHistory(fromMessagesAttribute(messages.l()), historyTurns);
    }

    /**
     * Añade un nuevo turno (mensaje del usuario seguido de la respuesta del asistente)
     * a la sesión indicada y actualiza el registro en DynamoDB con marcas de tiempo.
     *
     * @param sessionId Identificador de la sesión.
     * @param userMessage Mensaje enviado por el usuario.
     * @param assistantMessage Respuesta generada por el asistente LLM.
     */
    public void appendTurn(String sessionId, ChatMessage userMessage, ChatMessage assistantMessage) {
        Map<String, AttributeValue> existing = getItem(sessionId);
        List<ChatMessage> history;
        if (existing == null) {
            history = new ArrayList<>();
        } else {
            AttributeValue messages = existing.get("messages");
            history = messages != null && messages.hasL()
                    ? fromMessagesAttribute(messages.l())
                    : new ArrayList<>();
        }
        String createdAt = existing != null && existing.containsKey("created_at")
                ? existing.get("created_at").s() : Instant.now().toString();

        history.add(userMessage);
        history.add(assistantMessage);
        history = trimHistory(history, historyTurns);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("session_id", AttributeValue.builder().s(sessionId).build());
        item.put("messages", toMessagesAttribute(history));
        item.put("created_at", AttributeValue.builder().s(createdAt).build());
        item.put("updated_at", AttributeValue.builder().s(Instant.now().toString()).build());
        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
        logger.info("Sesion {} actualizada exitosamente. Turnos almacenados: {}", sessionId, history.size() / 2);
    }

    /**
     * Consulta el ítem de la sesión por su clave primaria {@code session_id}.
     *
     * @param sessionId Clave de la sesión.
     * @return Mapa de atributos de DynamoDB o {@code null} si no existe.
     */
    private Map<String, AttributeValue> getItem(String sessionId) {
        return dynamoDb.getItem(GetItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of("session_id", AttributeValue.builder().s(sessionId).build()))
                        .build())
                .item();
    }

    /**
     * Transforma una lista de {@link ChatMessage} al formato de atributo List (L) de DynamoDB,
     * encapsulando cada mensaje como un Map (M) con {@code role}, {@code content} y {@code ts}.
     *
     * @param messages Lista de mensajes a convertir.
     * @return Objeto {@link AttributeValue} de tipo lista.
     */
    static AttributeValue toMessagesAttribute(List<ChatMessage> messages) {
        List<AttributeValue> values = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, AttributeValue> fields = new HashMap<>();
            fields.put("role", AttributeValue.builder().s(message.role()).build());
            fields.put("content", AttributeValue.builder().s(message.content()).build());
            fields.put("ts", AttributeValue.builder().s(Instant.now().toString()).build());
            values.add(AttributeValue.builder().m(fields).build());
        }
        return AttributeValue.builder().l(values).build();
    }

    /**
     * Convierte la lista de atributos de DynamoDB de vuelta a objetos {@link ChatMessage}.
     *
     * @param values Lista de {@link AttributeValue} proveniente de DynamoDB.
     * @return Lista ordenada de {@link ChatMessage}.
     */
    static List<ChatMessage> fromMessagesAttribute(List<AttributeValue> values) {
        List<ChatMessage> messages = new ArrayList<>();
        for (AttributeValue value : values) {
            Map<String, AttributeValue> fields = value.m();
            if (fields == null || !fields.containsKey("role") || !fields.containsKey("content")) {
                continue;
            }
            messages.add(new ChatMessage(fields.get("role").s(), fields.get("content").s()));
        }
        return messages;
    }

    /**
     * Recorta la lista de mensajes conservando únicamente los últimos <em>N</em> turnos completos
     * (equivalente a {@code turns * 2} mensajes).
     *
     * @param messages Historial de mensajes.
     * @param turns Cantidad de turnos máximos a retener.
     * @return Sublista recortada.
     */
    static List<ChatMessage> trimHistory(List<ChatMessage> messages, int turns) {
        int keep = Math.max(turns, 1) * 2;
        if (messages.size() <= keep) {
            return messages;
        }
        return new ArrayList<>(messages.subList(messages.size() - keep, messages.size()));
    }

    /**
     * Parsea la variable de entorno para turnos de historial.
     *
     * @param value Cadena con el valor numérico.
     * @return Número entero positivo o valor por defecto.
     */
    private static int parseHistoryTurns(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_HISTORY_TURNS;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : DEFAULT_HISTORY_TURNS;
        } catch (NumberFormatException e) {
            return DEFAULT_HISTORY_TURNS;
        }
    }

    /**
     * Obtiene una variable de entorno requerida o arroja excepción descriptiva.
     *
     * @param name Nombre de la variable.
     * @return Valor de la variable.
     * @throws IllegalStateException Si la variable no está configurada.
     */
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}