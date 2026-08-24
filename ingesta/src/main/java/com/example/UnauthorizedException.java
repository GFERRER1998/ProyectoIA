package com.example;

/** Marca una petición cuyo ID Token Cognito no puede autenticarse. */
public class UnauthorizedException extends Exception {

    /** Crea el error con una descripción interna para CloudWatch. */
    public UnauthorizedException(String message) {
        super(message);
    }

    /** Crea el error conservando la causa criptográfica original. */
    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
