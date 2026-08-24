package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Genera URLs S3 prefirmadas para que un usuario autenticado suba un PDF. */
public class UploadUrlHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Gson GSON = new Gson();
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private final S3Presigner presigner;
    private final CognitoJwtVerifier jwtVerifier;
    private final String bucket;
    private final DocumentStore documentStore;

    /** Constructor usado por Lambda; carga bucket, región y Cognito desde entorno. */
    public UploadUrlHandler() {
        this(S3Presigner.builder().region(Region.of(required("AWS_REGION")))
                        .credentialsProvider(DefaultCredentialsProvider.create()).build(),
                CognitoJwtVerifier.fromEnvironment(), required("DOCUMENTS_BUCKET"),
                DocumentStore.fromEnvironment());
    }

    /** Constructor inyectable para pruebas sin llamadas a AWS. */
    UploadUrlHandler(S3Presigner presigner, CognitoJwtVerifier jwtVerifier, String bucket) {
        this(presigner, jwtVerifier, bucket, null);
    }

    /** Constructor inyectable que permite probar la persistencia de metadata por separado. */
    UploadUrlHandler(S3Presigner presigner, CognitoJwtVerifier jwtVerifier, String bucket,
                     DocumentStore documentStore) {
        this.presigner = presigner;
        this.jwtVerifier = jwtVerifier;
        this.bucket = bucket;
        this.documentStore = documentStore;
    }

    /** Autentica la petición, valida el PDF y devuelve los datos de subida directa a S3. */
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String userId = jwtVerifier.verify(findAuthorization(event));
            UploadRequest request = parse(event);
            validate(request);
            String documentId = UUID.randomUUID().toString();
            String objectKey = buildObjectKey(userId, documentId, request.fileName());
            PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(objectKey)
                    .contentType("application/pdf").build();
            PresignedPutObjectRequest signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10)).putObjectRequest(put).build());
            if (documentStore != null) {
                documentStore.createPending(documentId, userId, objectKey, request.fileName(),
                        request.contentType(), request.size());
            }

            JsonObject response = new JsonObject();
            response.addProperty("uploadUrl", signed.url().toString());
            response.addProperty("objectKey", objectKey);
            response.addProperty("documentId", documentId);
            return jsonResponse(200, response.toString());
        } catch (UnauthorizedException e) {
            return error(401, "No autenticado. Se requiere un ID Token de Cognito valido.");
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (Exception e) {
            return error(500, "No se pudo preparar la subida.");
        }
    }

    /** Lee el body JSON y convierte la petición en un objeto tipado. */
    static UploadRequest parse(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getBody() == null || event.getBody().isBlank()) {
            throw new IllegalArgumentException("El body es obligatorio");
        }
        try {
            JsonObject body = GSON.fromJson(event.getBody(), JsonObject.class);
            return new UploadRequest(body.get("fileName").getAsString(),
                    body.get("contentType").getAsString(), body.get("size").getAsLong());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Body de subida invalido");
        }
    }

    /** Aplica las restricciones de seguridad y capacidad antes de firmar la URL. */
    static void validate(UploadRequest request) {
        if (request.fileName() == null || request.fileName().isBlank()
                || !request.fileName().toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Solo se permiten archivos PDF");
        }
        if (!"application/pdf".equalsIgnoreCase(request.contentType())) {
            throw new IllegalArgumentException("El contentType debe ser application/pdf");
        }
        if (request.size() <= 0 || request.size() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("El PDF debe pesar entre 1 byte y 50 MB");
        }
    }

    /** Construye una ruta privada por usuario y evita traversal o caracteres peligrosos. */
    static String buildObjectKey(String userId, String documentId, String fileName) {
        String safeName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return "documents/" + userId + "/" + documentId + "-" + safeName;
    }

    /** Busca Authorization sin depender de las mayúsculas usadas por el proxy. */
    private static String findAuthorization(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getHeaders() == null) {
            return null;
        }
        return event.getHeaders().entrySet().stream()
                .filter(entry -> entry.getKey() != null && "authorization".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** Devuelve un error HTTP JSON sin exponer detalles internos. */
    private static APIGatewayV2HTTPResponse error(int status, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return jsonResponse(status, body.toString());
    }

    /** Construye una respuesta HTTP JSON con el content type correcto. */
    private static APIGatewayV2HTTPResponse jsonResponse(int status, String body) {
        return APIGatewayV2HTTPResponse.builder().withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json")).withBody(body).build();
    }

    /** Obtiene una variable de entorno obligatoria. */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta variable de entorno: " + name);
        }
        return value;
    }

    /** Datos mínimos solicitados por el frontend para generar una URL prefirmada. */
    record UploadRequest(String fileName, String contentType, long size) { }
}
