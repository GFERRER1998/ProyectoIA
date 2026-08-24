package com.example;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prueba autenticacion, validacion y generacion de URLs sin llamar a AWS. */
class UploadUrlHandlerTest {

    private S3Presigner presigner;
    private UploadUrlHandler handler;

    /** Prepara un presigner local con credenciales ficticias y un usuario controlado. */
    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("access", "secret"))).build();
        CognitoJwtVerifier verifier = new CognitoJwtVerifier("us-east-1", "pool", "client") {
            @Override
            public String verify(String authorization) throws UnauthorizedException {
                if (authorization == null) {
                    throw new UnauthorizedException("token ausente");
                }
                return "user-123";
            }
        };
        handler = new UploadUrlHandler(presigner, verifier, "documents-bucket");
    }

    /** Libera el cliente local después de cada prueba. */
    @AfterEach
    void tearDown() {
        presigner.close();
    }

    /** Verifica que un PDF válido produzca URL, key privada y documentId. */
    @Test
    void validPdfReturnsPresignedUploadData() {
        APIGatewayV2HTTPEvent event = event("Bearer token",
                "{\"fileName\":\"manual.pdf\",\"contentType\":\"application/pdf\",\"size\":1000}");

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(200, response.getStatusCode());
        var json = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertTrue(json.get("uploadUrl").getAsString().startsWith("https://documents-bucket.s3"));
        assertTrue(json.get("objectKey").getAsString().startsWith("documents/user-123/"));
        assertTrue(json.get("objectKey").getAsString().endsWith("-manual.pdf"));
        assertTrue(json.get("documentId").getAsString().length() > 10);
    }

    /** Verifica que extensiones no PDF reciban HTTP 400. */
    @Test
    void nonPdfIsRejected() {
        APIGatewayV2HTTPEvent event = event("Bearer token",
                "{\"fileName\":\"manual.txt\",\"contentType\":\"text/plain\",\"size\":1000}");

        assertEquals(400, handler.handleRequest(event, null).getStatusCode());
    }

    /** Verifica que una petición sin Authorization reciba HTTP 401. */
    @Test
    void missingTokenIsRejected() {
        APIGatewayV2HTTPEvent event = event(null,
                "{\"fileName\":\"manual.pdf\",\"contentType\":\"application/pdf\",\"size\":1000}");

        assertEquals(401, handler.handleRequest(event, null).getStatusCode());
    }

    /** Construye un evento HTTP v2 con body y header configurables. */
    private static APIGatewayV2HTTPEvent event(String authorization, String body) {
        Map<String, String> headers = authorization == null
                ? Map.of() : Map.of("Authorization", authorization);
        return APIGatewayV2HTTPEvent.builder().withHeaders(headers).withBody(body).build();
    }
}
