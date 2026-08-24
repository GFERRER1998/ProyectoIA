package com.example;

import com.google.gson.Gson;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.JWSHeader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URL;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CognitoJwtVerifierTest {

    private static final String REGION = "us-east-1";
    private static final String USER_POOL_ID = "us-east-1_TEST";
    private static final String CLIENT_ID = "test-client";
    private static final String ISSUER = "https://cognito-idp." + REGION + ".amazonaws.com/" + USER_POOL_ID;

    private RSAKey signingKey;
    private HttpServer jwksServer;
    private CognitoJwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String jwks = new Gson().toJson(new JWKSet(signingKey.toPublicJWK()).toJSONObject());
        jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] body = jwks.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        jwksServer.start();
        URL jwksUrl = new URL("http://localhost:" + jwksServer.getAddress().getPort() + "/jwks");
        verifier = new CognitoJwtVerifier(REGION, USER_POOL_ID, CLIENT_ID, jwksUrl);
    }

    @AfterEach
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void validTokenReturnsSubject() throws Exception {
        String token = token(Instant.now().plusSeconds(300), ISSUER, "user-123");

        assertEquals("user-123", verifier.verify("Bearer " + token));
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = token(Instant.now().minusSeconds(60), ISSUER, "user-123");

        assertThrows(UnauthorizedException.class, () -> verifier.verify("Bearer " + token));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String token = token(Instant.now().plusSeconds(300), "https://issuer.invalid", "user-123");

        assertThrows(UnauthorizedException.class, () -> verifier.verify("Bearer " + token));
    }

    @Test
    void missingOrMalformedAuthorizationIsRejected() {
        assertThrows(UnauthorizedException.class, () -> verifier.verify(null));
        assertThrows(UnauthorizedException.class, () -> verifier.verify("Basic token"));
    }

    private String token(Instant expiration, String issuer, String subject) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(CLIENT_ID)
                .claim("token_use", "id")
                .expirationTime(Date.from(expiration))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        JWSSigner signer = new RSASSASigner(signingKey.toPrivateKey());
        jwt.sign(signer);
        return jwt.serialize();
    }
}
