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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Verifica tokens JWT emitidos por Amazon Cognito User Pool.
 *
 * <p>Al inicializarse descarga las claves públicas JWKS del User Pool y las cachea
 * en memoria. Gracias al ciclo de vida de los objetos estáticos en Lambda, el cache
 * persiste entre invocaciones del mismo contenedor (warm starts), evitando latencia
 * de red adicional en la mayoría de las ejecuciones.</p>
 *
 * <p>El flujo de verificación sigue las recomendaciones oficiales de AWS para Cognito:</p>
 * <ol>
 *   <li>Verificar que el header {@code Authorization} contenga un Bearer token.</li>
 *   <li>Descargar o usar el cache de las claves JWKS desde
 *       {@code https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json}.</li>
 *   <li>Verificar la firma RS256 del token contra las claves JWKS.</li>
 *   <li>Validar el claim {@code iss} (debe ser el User Pool correcto).</li>
 *   <li>Validar el claim {@code token_use} (debe ser {@code "id"}).</li>
 *   <li>Validar el claim {@code aud} (debe ser el App Client ID).</li>
 *   <li>Validar {@code exp} (token no expirado).</li>
 * </ol>
 */
public class CognitoJwtVerifier {

    private static final Logger logger = LoggerFactory.getLogger(CognitoJwtVerifier.class);

    /** Claim de Cognito que indica el tipo de token: "id" o "access". */
    private static final String CLAIM_TOKEN_USE = "token_use";

    /** Valor esperado para el claim token_use cuando se envía el ID Token. */
    private static final String TOKEN_USE_ID = "id";

    /** Región de AWS donde está el User Pool. */
    private final String region;

    /** Identificador del User Pool de Cognito (ej. "us-east-1_AbCdEfGhI"). */
    private final String userPoolId;

    /** Identificador del App Client de Cognito (audience del token). */
    private final String appClientId;

    /** Procesador de JWT de nimbus con claves JWKS cacheadas. */
    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    /**
     * Construye el verificador descargando las claves JWKS del User Pool indicado.
     *
     * @param region      Región de AWS del User Pool (ej. {@code "us-east-1"}).
     * @param userPoolId  ID del User Pool de Cognito.
     * @param appClientId ID del App Client (será validado como {@code aud}).
     * @throws IllegalStateException Si la URL del JWKS endpoint es inválida (error de configuración).
     */
    public CognitoJwtVerifier(String region, String userPoolId, String appClientId) {
        this.region = region;
        this.userPoolId = userPoolId;
        this.appClientId = appClientId;
        this.jwtProcessor = buildProcessor(region, userPoolId, appClientId);
    }

    /** Constructor alternativo para pruebas con un endpoint JWKS controlado. */
    CognitoJwtVerifier(String region, String userPoolId, String appClientId, URL jwksUrl) {
        this.region = region;
        this.userPoolId = userPoolId;
        this.appClientId = appClientId;
        this.jwtProcessor = buildProcessor(region, userPoolId, appClientId, jwksUrl);
    }

    /**
     * Crea el verificador leyendo configuración desde variables de entorno de la Lambda.
     *
     * <p>Variables de entorno requeridas:</p>
     * <ul>
     *   <li>{@code AWS_REGION}: Región de AWS (inyectada automáticamente por Lambda).</li>
     *   <li>{@code COGNITO_USER_POOL_ID}: ID del User Pool de Cognito.</li>
     *   <li>{@code COGNITO_APP_CLIENT_ID}: ID del App Client de Cognito.</li>
     * </ul>
     *
     * @return Instancia del verificador configurada con las variables de entorno.
     */
    public static CognitoJwtVerifier fromEnvironment() {
        String region = System.getenv("AWS_REGION");
        String userPoolId = System.getenv("COGNITO_USER_POOL_ID");
        String appClientId = System.getenv("COGNITO_APP_CLIENT_ID");
        if (region == null || userPoolId == null || appClientId == null) {
            throw new IllegalStateException(
                    "Faltan variables de entorno: AWS_REGION, COGNITO_USER_POOL_ID o COGNITO_APP_CLIENT_ID");
        }
        return new CognitoJwtVerifier(region, userPoolId, appClientId);
    }

    /**
     * Verifica el token JWT y retorna el {@code sub} (user ID único en Cognito) si es válido.
     *
     * @param bearerToken Valor completo del header {@code Authorization} (ej. {@code "Bearer eyJ..."}).
     * @return El claim {@code sub} del token, que identifica unívocamente al usuario.
     * @throws UnauthorizedException Si el token está ausente, tiene formato inválido,
     *                               la firma es incorrecta, el token está expirado,
     *                               o cualquier claim requerido no coincide.
     */
    public String verify(String bearerToken) throws UnauthorizedException {
        String token = extractToken(bearerToken);
        try {
            JWTClaimsSet claims = jwtProcessor.process(token, null);
            String sub = claims.getSubject();
            logger.info("Token JWT verificado correctamente. sub={}", sub);
            return sub;
        } catch (ParseException e) {
            throw new UnauthorizedException("Token JWT malformado: " + e.getMessage(), e);
        } catch (BadJOSEException e) {
            throw new UnauthorizedException("Token JWT invalido o expirado: " + e.getMessage(), e);
        } catch (JOSEException e) {
            throw new UnauthorizedException("Error de criptografia al verificar el JWT: " + e.getMessage(), e);
        }
    }

    /**
     * Extrae el token JWT puro del header Authorization (elimina el prefijo "Bearer ").
     *
     * @param bearerToken Valor del header Authorization.
     * @return Token JWT sin el prefijo "Bearer ".
     * @throws UnauthorizedException Si el header está ausente o no tiene el formato esperado.
     */
    private static String extractToken(String bearerToken) throws UnauthorizedException {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new UnauthorizedException("Header Authorization ausente. Se requiere 'Bearer <token>'.");
        }
        String prefix = "Bearer ";
        if (!bearerToken.startsWith(prefix)) {
            throw new UnauthorizedException("Header Authorization invalido. Formato esperado: 'Bearer <token>'.");
        }
        String token = bearerToken.substring(prefix.length()).strip();
        if (token.isEmpty()) {
            throw new UnauthorizedException("Token JWT vacio en el header Authorization.");
        }
        return token;
    }

    /**
     * Construye y configura el procesador JWT de nimbus con las claves JWKS del User Pool.
     *
     * <p>El {@link RemoteJWKSet} descarga y cachea las claves JWKS automáticamente.
     * La primera invocación hace una llamada HTTP al endpoint de Cognito; las siguientes
     * reutilizan el cache en memoria mientras el contenedor Lambda esté activo.</p>
     *
     * @param region      Región de AWS del User Pool.
     * @param userPoolId  ID del User Pool de Cognito.
     * @param appClientId ID del App Client (audience).
     * @return Procesador JWT configurado y listo para usar.
     * @throws IllegalStateException Si la URL JWKS no se puede construir.
     */
    private static ConfigurableJWTProcessor<SecurityContext> buildProcessor(
            String region, String userPoolId, String appClientId) {

        String jwksUrl = String.format(
                "https://cognito-idp.%s.amazonaws.com/%s/.well-known/jwks.json", region, userPoolId);
        URL jwkSetUrl;
        try {
            jwkSetUrl = new URL(jwksUrl);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("URL JWKS invalida: " + jwksUrl, e);
        }

        return buildProcessor(region, userPoolId, appClientId, jwkSetUrl);
    }

    private static ConfigurableJWTProcessor<SecurityContext> buildProcessor(
            String region, String userPoolId, String appClientId, URL jwkSetUrl) {
        String issuer = String.format(
                "https://cognito-idp.%s.amazonaws.com/%s", region, userPoolId);
        JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(jwkSetUrl);
        ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

        // Selector de clave: solo aceptar tokens firmados con RS256 (algoritmo estándar de Cognito).
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));

        // Validador de claims: iss, aud, exp, nbf y el claim personalizado token_use=id.
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                appClientId,   // audience esperado
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .claim(CLAIM_TOKEN_USE, TOKEN_USE_ID)
                        .build(),
                new HashSet<>(Arrays.asList("sub", "iss", "aud", "exp", CLAIM_TOKEN_USE))
        ));

        return processor;
    }
}
