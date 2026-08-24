package com.example;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifica que el handler de documentos rechace peticiones sin autenticacion. */
class DocumentHandlerTest {

    private S3Presigner presigner;
    private DocumentHandler handler;

    /** Prepara dependencias locales y un verificador controlado para la prueba. */
    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("access", "secret"))).build();
        CognitoJwtVerifier verifier = new CognitoJwtVerifier("us-east-1", "pool", "client") {
            @Override
            public String verify(String authorization) throws UnauthorizedException {
                if (authorization == null) throw new UnauthorizedException("token ausente");
                return "user-123";
            }
        };
        handler = new DocumentHandler(null, presigner, "documents-bucket", verifier);
    }

    /** Libera el presigner local al terminar cada caso. */
    @AfterEach
    void tearDown() {
        presigner.close();
    }

    /** Comprueba que una peticion sin token devuelva HTTP 401 antes de acceder a DynamoDB. */
    @Test
    void missingTokenIsRejected() {
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withHeaders(Map.of()).withRawPath("/documents").build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(401, response.getStatusCode());
    }
}
