package com.example;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Persiste metadata y estado de documentos en DynamoDB. */
public class DocumentStore {

    /** Estados posibles durante el ciclo de vida de un documento. */
    public enum Status { PENDING, PROCESSING, READY, ERROR }

    private final DynamoDbClient dynamoDb;
    private final String tableName;
    private final String userIndexName;

    /** Construye el store usando la tabla y el índice configurados en Lambda. */
    public DocumentStore(DynamoDbClient dynamoDb, String tableName, String userIndexName) {
        if (dynamoDb == null || tableName == null || tableName.isBlank()
                || userIndexName == null || userIndexName.isBlank()) {
            throw new IllegalArgumentException("DynamoDB client, table and user index are required");
        }
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
        this.userIndexName = userIndexName;
    }

    /** Crea una instancia de producción leyendo la configuración del entorno. */
    public static DocumentStore fromEnvironment() {
        return new DocumentStore(DynamoDbClient.builder().build(), required("DOCUMENTS_TABLE"),
                required("DOCUMENTS_USER_INDEX"));
    }

    /** Guarda metadata inicial antes de que el navegador suba el archivo a S3. */
    public void createPending(String documentId, String userId, String objectKey, String fileName,
                              String contentType, long size) {
        String now = Instant.now().toString();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("document_id", text(documentId));
        item.put("user_id", text(userId));
        item.put("object_key", text(objectKey));
        item.put("file_name", text(fileName));
        item.put("content_type", text(contentType));
        item.put("size", AttributeValue.builder().n(Long.toString(size)).build());
        item.put("status", text(Status.PENDING.name()));
        item.put("created_at", text(now));
        item.put("updated_at", text(now));
        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    /** Actualiza el estado y fecha de un documento identificado por su ID. */
    public void updateStatus(String documentId, Status status, String errorMessage) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", text(status.name()));
        values.put(":updated", text(Instant.now().toString()));
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        String expression = "SET #status = :status, updated_at = :updated";
        if (errorMessage != null && !errorMessage.isBlank()) {
            values.put(":error", text(errorMessage));
            expression += ", error_message = :error";
        } else {
            expression += " REMOVE error_message";
        }
        dynamoDb.updateItem(UpdateItemRequest.builder().tableName(tableName)
                .key(Map.of("document_id", text(documentId))).updateExpression(expression)
                .expressionAttributeNames(names).expressionAttributeValues(values).build());
    }

    /** Obtiene un documento por ID para que el handler pueda validar su propietario. */
    public DocumentRecord get(String documentId) {
        Map<String, AttributeValue> item = dynamoDb.getItem(GetItemRequest.builder().tableName(tableName)
                .key(Map.of("document_id", text(documentId))).build()).item();
        return item == null || item.isEmpty() ? null : toRecord(item);
    }

    /** Elimina la metadata de un documento después de validar su propietario en el handler. */
    public void delete(String documentId) {
        dynamoDb.deleteItem(DeleteItemRequest.builder().tableName(tableName)
                .key(Map.of("document_id", text(documentId))).build());
    }

    /** Lista los documentos de un usuario usando el índice secundario, nunca un Scan global. */
    public List<DocumentRecord> listByUser(String userId) {
        List<DocumentRecord> result = new ArrayList<>();
        QueryRequest request = QueryRequest.builder().tableName(tableName).indexName(userIndexName)
                .keyConditionExpression("user_id = :user")
                .expressionAttributeValues(Map.of(":user", text(userId))).scanIndexForward(false).build();
        dynamoDb.queryPaginator(request).items().forEach(item -> result.add(toRecord(item)));
        return result;
    }

    /** Convierte un item DynamoDB a un modelo seguro para las respuestas HTTP. */
    private static DocumentRecord toRecord(Map<String, AttributeValue> item) {
        return new DocumentRecord(value(item, "document_id"), value(item, "user_id"),
                value(item, "object_key"), value(item, "file_name"), value(item, "content_type"),
                number(item, "size"), value(item, "status"), value(item, "created_at"),
                value(item, "updated_at"), value(item, "error_message"));
    }

    /** Crea un atributo textual compatible con DynamoDB. */
    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    /** Lee un atributo textual tolerando metadata antigua o incompleta. */
    private static String value(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.s() == null ? null : value.s();
    }

    /** Lee un tamaño numérico y devuelve cero si el atributo no existe. */
    private static long number(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? 0L : Long.parseLong(value.n());
    }

    /** Obtiene una variable obligatoria y falla rápido ante una configuración incompleta. */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    /** Modelo de metadata que se expone al handler HTTP. */
    public record DocumentRecord(String documentId, String userId, String objectKey, String fileName,
                                 String contentType, long size, String status, String createdAt,
                                 String updatedAt, String errorMessage) { }
}
