# CognitoJwtVerifier de Ingesta

Esta clase valida los ID Tokens de Cognito usados para solicitar URLs S3.

Valida:

- Firma RS256 mediante JWKS.
- `iss` del User Pool.
- `aud` del App Client.
- `token_use=id`.
- `sub` obligatorio.
- Expiración del token.

Variables requeridas:

```text
AWS_REGION
COGNITO_USER_POOL_ID
COGNITO_APP_CLIENT_ID
```

Las claves públicas se cachean internamente por `RemoteJWKSet` durante la vida
del contenedor Lambda.
