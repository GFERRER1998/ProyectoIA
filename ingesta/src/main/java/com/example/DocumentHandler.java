package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.time.Duration;
import java.util.Map;

/** Expone listado, detalle y visualización temporal de documentos del usuario. */
public class DocumentHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final DocumentStore documentStore;
    private final S3Presigner presigner;
    private final String bucket;
    private final CognitoJwtVerifier jwtVerifier;
    private final S3Client s3Client;
    private final PineconeClient pineconeClient;

    /** Construye el handler de producción usando DynamoDB, S3 y Cognito. */
    public DocumentHandler() {
        this(DocumentStore.fromEnvironment(),
                S3Presigner.builder().region(Region.of(required("AWS_REGION")))
                        .credentialsProvider(DefaultCredentialsProvider.create()).build(),
                required("DOCUMENTS_BUCKET"), CognitoJwtVerifier.fromEnvironment(),
                S3Client.builder().build(), PineconeClient.fromEnvironment());
    }

    /** Constructor inyectable para pruebas unitarias sin recursos AWS reales. */
    DocumentHandler(DocumentStore documentStore, S3Presigner presigner, String bucket,
                    CognitoJwtVerifier jwtVerifier) {
        this(documentStore, presigner, bucket, jwtVerifier, null, null);
    }

    DocumentHandler(DocumentStore documentStore, S3Presigner presigner, String bucket,
                    CognitoJwtVerifier jwtVerifier, S3Client s3Client, PineconeClient pineconeClient) {
        this.documentStore = documentStore;
        this.presigner = presigner;
        this.bucket = bucket;
        this.jwtVerifier = jwtVerifier;
        this.s3Client = s3Client;
        this.pineconeClient = pineconeClient;
    }

    /** Autentica la petición y enruta la operación según método y ruta HTTP. */
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String userId = jwtVerifier.verify(authorizationHeader(event));
            String path = event == null || event.getRawPath() == null ? "/documents" : event.getRawPath();
            String method = event == null || event.getRequestContext() == null
                    || event.getRequestContext().getHttp() == null ? "GET"
                    : event.getRequestContext().getHttp().getMethod();
            if ("GET".equalsIgnoreCase(method) && "/documents".equals(path)) {
                return list(userId);
            }
            if ("GET".equalsIgnoreCase(method) && path.endsWith("/view-url")) {
                return viewUrl(userId, documentId(path));
            }
            if ("GET".equalsIgnoreCase(method)) {
                return detail(userId, documentId(path));
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                return delete(userId, documentId(path));
            }
            return error(405, "Metodo no permitido");
        } catch (UnauthorizedException e) {
            return error(401, "No autenticado. Se requiere un ID Token de Cognito valido.");
        } catch (DocumentNotFoundException e) {
            return error(404, "Documento no encontrado");
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (Exception e) {
            return error(500, "No se pudo consultar el documento.");
        }
    }

    /** Devuelve el listado de documentos pertenecientes al usuario autenticado. */
    private APIGatewayV2HTTPResponse list(String userId) {
        JsonArray documents = new JsonArray();
        documentStore.listByUser(userId).forEach(record -> documents.add(publicJson(record)));
        JsonObject body = new JsonObject();
        body.add("documents", documents);
        return jsonResponse(200, body.toString());
    }

    /** Devuelve el detalle de un documento después de validar propiedad. */
    private APIGatewayV2HTTPResponse detail(String userId, String documentId) {
        DocumentStore.DocumentRecord record = ownedDocument(userId, documentId);
        return jsonResponse(200, publicJson(record).toString());
    }

    /** Genera una URL GET de S3 válida durante diez minutos para visualizar un PDF. */
    private APIGatewayV2HTTPResponse viewUrl(String userId, String documentId) {
        DocumentStore.DocumentRecord record = ownedDocument(userId, documentId);
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(record.objectKey()).build();
        PresignedGetObjectRequest signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10)).getObjectRequest(get).build());
        JsonObject body = new JsonObject();
        body.addProperty("documentId", record.documentId());
        body.addProperty("fileName", record.fileName());
        body.addProperty("viewUrl", signed.url().toString());
        body.addProperty("expiresIn", 600);
        return jsonResponse(200, body.toString());
    }

    /** Elimina chunks, objeto S3 y metadata únicamente de un documento propio. */
    private APIGatewayV2HTTPResponse delete(String userId, String documentId) {
        DocumentStore.DocumentRecord record = ownedDocument(userId, documentId);
        if (pineconeClient == null || s3Client == null) {
            throw new IllegalStateException("Document deletion is not configured");
        }
        pineconeClient.deleteBySource(bucket, record.objectKey());
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(record.objectKey()).build());
        documentStore.delete(record.documentId());
        return APIGatewayV2HTTPResponse.builder().withStatusCode(204).build();
    }

    /** Comprueba existencia y propiedad antes de devolver cualquier recurso. */
    private DocumentStore.DocumentRecord ownedDocument(String userId, String documentId) {
        DocumentStore.DocumentRecord record = documentStore.get(documentId);
        if (record == null || !userId.equals(record.userId())) {
            throw new DocumentNotFoundException();
        }
        return record;
    }

    /** Extrae el UUID final de las rutas de detalle y visualización. */
    private static String documentId(String path) {
        String[] parts = path.split("/");
        if (parts.length < 3 || parts[2].isBlank()) {
            throw new IllegalArgumentException("Document ID invalido");
        }
        return parts[2];
    }

    /** Convierte metadata interna a la representación pública sin userId ni errores internos. */
    private static JsonObject publicJson(DocumentStore.DocumentRecord record) {
        JsonObject json = new JsonObject();
        json.addProperty("documentId", record.documentId());
        json.addProperty("fileName", record.fileName());
        json.addProperty("contentType", record.contentType());
        json.addProperty("size", record.size());
        json.addProperty("status", record.status());
        json.addProperty("createdAt", record.createdAt());
        json.addProperty("updatedAt", record.updatedAt());
        return json;
    }

    /** Obtiene el header Authorization sin depender de su capitalización. */
    private static String authorizationHeader(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getHeaders() == null) return null;
        return event.getHeaders().entrySet().stream()
                .filter(entry -> entry.getKey() != null && "authorization".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** Construye una respuesta de error sin revelar detalles del backend. */
    private static APIGatewayV2HTTPResponse error(int status, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return jsonResponse(status, body.toString());
    }

    /** Construye respuestas JSON con el header requerido por el frontend. */
    private static APIGatewayV2HTTPResponse jsonResponse(int status, String body) {
        return APIGatewayV2HTTPResponse.builder().withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json")).withBody(body).build();
    }

    /** Obtiene una variable de entorno obligatoria para iniciar Lambda. */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }

    /** Excepcion interna para diferenciar un recurso inexistente de un input invalido. */
    private static final class DocumentNotFoundException extends RuntimeException { }
}
