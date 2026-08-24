package com.example;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifica que el endpoint de historial exija autenticacion antes de leer DynamoDB. */
class SessionHandlerTest {

    /** Comprueba que una peticion sin token reciba HTTP 401. */
    @Test
    void missingTokenIsRejected() {
        CognitoJwtVerifier verifier = new CognitoJwtVerifier("us-east-1", "pool", "client") {
            @Override
            public String verify(String authorization) throws UnauthorizedException {
                if (authorization == null) throw new UnauthorizedException("token ausente");
                return "user-123";
            }
        };
        SessionHandler handler = new SessionHandler(null, verifier);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
                .withHeaders(Map.of()).withRawPath("/sessions").build();

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, null);

        assertEquals(401, response.getStatusCode());
    }
}
