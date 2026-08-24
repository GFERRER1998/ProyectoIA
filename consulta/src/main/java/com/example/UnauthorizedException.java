package com.example;

/**
 * Excepción lanzada cuando un token JWT de Cognito está ausente, expirado o es inválido.
 *
 * <p>Se diferencia de {@link IllegalArgumentException} (errores de input del usuario, HTTP 400)
 * y de las excepciones genéricas de servicios externos (HTTP 502). Esta excepción produce
 * una respuesta {@code HTTP 401 Unauthorized} al cliente.</p>
 */
public class UnauthorizedException extends Exception {

    /**
     * Construye la excepción con un mensaje descriptivo del motivo del rechazo.
     *
     * @param message Descripción del problema de autenticación (no se expone al cliente externo,
     *                solo se registra en CloudWatch Logs).
     */
    public UnauthorizedException(String message) {
        super(message);
    }

    /**
     * Construye la excepción incluyendo la causa raíz para facilitar el diagnóstico en logs.
     *
     * @param message Descripción del problema de autenticación.
     * @param cause   Excepción original (ej. ParseException, BadJOSEException de nimbus).
     */
    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
