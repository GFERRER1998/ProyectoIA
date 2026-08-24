package com.example;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;

/** Verifica ID Tokens Cognito para proteger la API de URLs prefirmadas. */
public class CognitoJwtVerifier {

    private static final String TOKEN_USE_CLAIM = "token_use";
    private static final String ID_TOKEN_USE = "id";
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    /** Construye el verificador con el endpoint JWKS oficial del User Pool. */
    public CognitoJwtVerifier(String region, String userPoolId, String appClientId) {
        this.processor = buildProcessor(region, userPoolId, appClientId);
    }

    /** Construye el verificador usando las variables estándar de Lambda. */
    public static CognitoJwtVerifier fromEnvironment() {
        return new CognitoJwtVerifier(required("AWS_REGION"),
                required("COGNITO_USER_POOL_ID"), required("COGNITO_APP_CLIENT_ID"));
    }

    /** Verifica el header Bearer y devuelve el sub del usuario autenticado. */
    public String verify(String authorization) throws UnauthorizedException {
        String token = extractToken(authorization);
        try {
            String subject = processor.process(token, null).getSubject();
            if (subject == null || subject.isBlank()) {
                throw new UnauthorizedException("El token no contiene sub");
            }
            return subject;
        } catch (ParseException | BadJOSEException | JOSEException e) {
            throw new UnauthorizedException("ID Token Cognito invalido", e);
        }
    }

    /** Extrae un JWT de un header Authorization con formato Bearer. */
    static String extractToken(String authorization) throws UnauthorizedException {
        if (authorization == null || authorization.isBlank()
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new UnauthorizedException("Se requiere Authorization: Bearer <ID_TOKEN>");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new UnauthorizedException("El ID Token esta vacio");
        }
        return token;
    }

    /** Construye el procesador Nimbus con firma, issuer, audience y token_use esperados. */
    private static ConfigurableJWTProcessor<SecurityContext> buildProcessor(
            String region, String userPoolId, String appClientId) {
        String issuer = "https://cognito-idp." + region + ".amazonaws.com/" + userPoolId;
        String jwks = issuer + "/.well-known/jwks.json";
        try {
            JWKSource<SecurityContext> keys = new RemoteJWKSet<>(new URL(jwks));
            ConfigurableJWTProcessor<SecurityContext> result = new DefaultJWTProcessor<>();
            result.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
            result.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(appClientId,
                    new JWTClaimsSet.Builder().issuer(issuer)
                            .claim(TOKEN_USE_CLAIM, ID_TOKEN_USE).build(),
                    new HashSet<>(Arrays.asList("sub", "iss", "aud", "exp", TOKEN_USE_CLAIM))));
            return result;
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Endpoint JWKS invalido", e);
        }
    }

    /** Obtiene una variable de entorno obligatoria y falla rápido si falta. */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta variable de entorno: " + name);
        }
        return value;
    }
}
