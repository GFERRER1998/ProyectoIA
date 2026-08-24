package com.example;

import com.example.DeepSeekClient.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

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
public class SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionStore.class);
    // Cantidad por defecto de turnos (pares usuario-asistente) que se conservan en memoria.
    private static final int DEFAULT_HISTORY_TURNS = 6;
    private static final int MAX_STORED_MESSAGE_LENGTH = 12_000;
    private static final int MAX_APPEND_ATTEMPTS = 3;

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final int historyTurns;
    private final String userIndexName;

    /**
     * Constructor estándar con la cantidad de turnos por defecto.
     *
     * @param dynamoDb Cliente SDK de DynamoDB.
     * @param tableName Nombre de la tabla de sesiones en DynamoDB.
     * @throws IllegalArgumentException Si el cliente o el nombre de tabla son nulos o vacíos.
     */
    public SessionStore(DynamoDbClient dynamoDb, String tableName) {
        this(dynamoDb, tableName, DEFAULT_HISTORY_TURNS, "user-updated-index");
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
        this(dynamoDb, tableName, historyTurns, "user-updated-index");
    }

    /** Constructor completo con el índice utilizado para listar sesiones por usuario. */
    SessionStore(DynamoDbClient dynamoDb, String tableName, int historyTurns, String userIndexName) {
        if (dynamoDb == null || tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("DynamoDb client and table name are required");
        }
        if (userIndexName == null || userIndexName.isBlank()) {
            throw new IllegalArgumentException("Session user index is required");
        }
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.historyTurns = historyTurns > 0 ? historyTurns : DEFAULT_HISTORY_TURNS;
        this.userIndexName = userIndexName;
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
                parseHistoryTurns(System.getenv("HISTORY_TURNS")),
                requiredEnvironment("SESSIONS_USER_INDEX"));
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
     * @param userId Identificador {@code sub} del usuario autenticado en Cognito.
     */
    public void appendTurn(String sessionId, String userId, ChatMessage userMessage,
                           ChatMessage assistantMessage) {
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Session id and user id are required");
        }
        ChatMessage storedUser = limitMessage(userMessage);
        ChatMessage storedAssistant = limitMessage(assistantMessage);
        for (int attempt = 1; attempt <= MAX_APPEND_ATTEMPTS; attempt++) {
            Map<String, AttributeValue> existing = getItem(sessionId);
            List<ChatMessage> history = existing == null ? new ArrayList<>() : messagesFrom(existing);
            String createdAt = existing != null && existing.containsKey("created_at")
                    ? existing.get("created_at").s() : Instant.now().toString();
            long version = existing == null ? 0L : longNumberValue(existing, "version");

            history.add(storedUser);
            history.add(storedAssistant);
            history = trimHistory(history, historyTurns);

            Map<String, AttributeValue> item = buildSessionItem(sessionId, userId, existing, history,
                    storedUser, storedAssistant, version + 1, createdAt);
            try {
                PutItemRequest.Builder request = PutItemRequest.builder().tableName(tableName).item(item);
                if (existing == null) {
                    request.conditionExpression("attribute_not_exists(session_id)");
                } else {
                    request.conditionExpression("attribute_not_exists(#version) OR #version = :expectedVersion")
                            .expressionAttributeNames(Map.of("#version", "version"))
                            .expressionAttributeValues(Map.of(":expectedVersion",
                                    AttributeValue.builder().n(Long.toString(version)).build()));
                }
                dynamoDb.putItem(request.build());
                logger.info("Sesion {} actualizada exitosamente. Turnos almacenados: {}",
                        sessionId, history.size() / 2);
                return;
            } catch (ConditionalCheckFailedException e) {
                if (attempt == MAX_APPEND_ATTEMPTS) {
                    throw new IllegalStateException("No se pudo actualizar la sesion por concurrencia", e);
                }
            }
        }
    }

    private static List<ChatMessage> messagesFrom(Map<String, AttributeValue> item) {
        AttributeValue messages = item.get("messages");
        return messages != null && messages.hasL()
                ? fromMessagesAttribute(messages.l()) : new ArrayList<>();
    }

    private static Map<String, AttributeValue> buildSessionItem(String sessionId, String userId,
                                                                  Map<String, AttributeValue> existing,
                                                                  List<ChatMessage> history,
                                                                  ChatMessage userMessage,
                                                                  ChatMessage assistantMessage,
                                                                  long version, String createdAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("session_id", AttributeValue.builder().s(sessionId).build());
        item.put("user_id", AttributeValue.builder().s(userId).build());
        item.put("messages", toMessagesAttribute(history));
        item.put("created_at", AttributeValue.builder().s(createdAt).build());
        item.put("updated_at", AttributeValue.builder().s(Instant.now().toString()).build());
        item.put("title", AttributeValue.builder().s(existingTitle(existing, userMessage)).build());
        item.put("last_message_preview", AttributeValue.builder()
                .s(preview(assistantMessage.content())).build());
        item.put("message_count", AttributeValue.builder().n(Integer.toString(history.size())).build());
        item.put("version", AttributeValue.builder().n(Long.toString(version)).build());
        return item;
    }

    /** Limita el contenido persistido para mantener el item lejos del límite de DynamoDB. */
    static ChatMessage limitMessage(ChatMessage message) {
        if (message == null || message.content() == null) {
            throw new IllegalArgumentException("Session messages are required");
        }
        String content = message.content();
        if (content.length() <= MAX_STORED_MESSAGE_LENGTH) return message;
        return new ChatMessage(message.role(), content.substring(0, MAX_STORED_MESSAGE_LENGTH) + "...");
    }

    /** Lista resúmenes de sesiones del usuario ordenados por actividad descendente. */
    public List<SessionSummary> listSessions(String userId) {
        List<SessionSummary> result = new ArrayList<>();
        QueryRequest request = QueryRequest.builder().tableName(tableName).indexName(userIndexName)
                .keyConditionExpression("user_id = :user")
                .expressionAttributeValues(Map.of(":user", AttributeValue.builder().s(userId).build()))
                .scanIndexForward(false).build();
        dynamoDb.queryPaginator(request).items().forEach(item -> result.add(toSummary(item, userId)));
        return result;
    }

    /** Obtiene todos los mensajes de una sesión después de validar su propietario. */
    public SessionDetail getSession(String userId, String clientSessionId) {
        Map<String, AttributeValue> item = getItem(fullSessionId(userId, clientSessionId));
        if (item == null || !userId.equals(stringValue(item, "user_id"))) return null;
        AttributeValue messages = item.get("messages");
        List<StoredMessage> stored = messages == null || !messages.hasL()
                ? List.of() : fromStoredMessages(messages.l());
        return new SessionDetail(clientSessionId, stringValue(item, "title"),
                stringValue(item, "created_at"), stringValue(item, "updated_at"), stored);
    }

    /** Elimina una sesión únicamente cuando pertenece al usuario autenticado. */
    public boolean deleteSession(String userId, String clientSessionId) {
        String sessionId = fullSessionId(userId, clientSessionId);
        Map<String, AttributeValue> item = getItem(sessionId);
        if (item == null || !userId.equals(stringValue(item, "user_id"))) return false;
        dynamoDb.deleteItem(DeleteItemRequest.builder().tableName(tableName)
                .key(Map.of("session_id", AttributeValue.builder().s(sessionId).build())).build());
        return true;
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

    /** Convierte mensajes DynamoDB conservando la marca temporal para la API de historial. */
    static List<StoredMessage> fromStoredMessages(List<AttributeValue> values) {
        List<StoredMessage> messages = new ArrayList<>();
        for (AttributeValue value : values) {
            Map<String, AttributeValue> fields = value.m();
            if (fields == null || stringValue(fields, "role") == null || stringValue(fields, "content") == null) {
                continue;
            }
            messages.add(new StoredMessage(stringValue(fields, "role"), stringValue(fields, "content"),
                    stringValue(fields, "ts")));
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

    /** Construye el identificador interno que aísla las sesiones por usuario. */
    private static String fullSessionId(String userId, String clientSessionId) {
        if (userId == null || userId.isBlank() || clientSessionId == null || clientSessionId.isBlank()
                || clientSessionId.contains("#")) {
            throw new IllegalArgumentException("Invalid session identifier");
        }
        return userId + "#" + clientSessionId;
    }

    /** Genera un título estable utilizando la primera pregunta de la conversación. */
    private static String existingTitle(Map<String, AttributeValue> existing, ChatMessage firstMessage) {
        String title = existing == null ? null : stringValue(existing, "title");
        return title == null || title.isBlank() ? preview(firstMessage.content()) : title;
    }

    /** Limita textos de navegación para no inflar el listado de sesiones. */
    private static String preview(String content) {
        if (content == null) return "";
        return content.length() <= 80 ? content : content.substring(0, 80) + "...";
    }

    /** Convierte un item DynamoDB al resumen público de sesión. */
    private static SessionSummary toSummary(Map<String, AttributeValue> item, String userId) {
        String fullId = stringValue(item, "session_id");
        String prefix = userId + "#";
        String clientId = fullId != null && fullId.startsWith(prefix) ? fullId.substring(prefix.length()) : fullId;
        List<StoredMessage> messages = item.get("messages") == null || !item.get("messages").hasL()
                ? List.of() : fromStoredMessages(item.get("messages").l());
        String title = stringValue(item, "title");
        if ((title == null || title.isBlank()) && !messages.isEmpty()) {
            title = preview(messages.get(0).content());
        }
        String preview = stringValue(item, "last_message_preview");
        if ((preview == null || preview.isBlank()) && !messages.isEmpty()) {
            preview = preview(messages.get(messages.size() - 1).content());
        }
        int messageCount = numberValue(item, "message_count");
        if (messageCount == 0) messageCount = messages.size();
        return new SessionSummary(clientId, title, preview, messageCount,
                stringValue(item, "created_at"), stringValue(item, "updated_at"));
    }

    /** Lee un atributo de texto sin fallar ante registros antiguos o incompletos. */
    private static String stringValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    /** Lee el contador de mensajes y devuelve cero cuando no existe. */
    private static int numberValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? 0 : Integer.parseInt(value.n());
    }

    private static long longNumberValue(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? 0L : Long.parseLong(value.n());
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

    /** Resumen utilizado por la lista de conversaciones del frontend. */
    public record SessionSummary(String sessionId, String title, String lastMessagePreview,
                                 int messageCount, String createdAt, String updatedAt) { }

    /** Detalle completo utilizado al abrir una conversación anterior. */
    public record SessionDetail(String sessionId, String title, String createdAt, String updatedAt,
                                List<StoredMessage> messages) { }

    /** Mensaje persistido con su marca temporal original. */
    public record StoredMessage(String role, String content, String timestamp) { }
}
