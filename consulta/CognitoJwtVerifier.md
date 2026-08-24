# Autenticacion con Amazon Cognito

## Arquitectura

La Function URL permanece con `AuthType: NONE`. La Lambda valida manualmente el
ID Token de Cognito antes de ejecutar Pinecone, OpenRouter o DynamoDB.

```text
Frontend -> Authorization: Bearer <ID_TOKEN>
         -> Function URL
         -> CognitoJwtVerifier
         -> QueryHandler
```

`CognitoJwtVerifier` obtiene las claves publicas del endpoint JWKS del User Pool
y las reutiliza durante los warm starts de Lambda. Se validan la firma RS256,
`iss`, `aud`, `token_use=id`, `sub` y expiracion.

## Configuracion

SAM crea automaticamente un User Pool con login por email y un App Client
publico sin secreto. La Lambda recibe `COGNITO_USER_POOL_ID` y
`COGNITO_APP_CLIENT_ID` mediante variables de entorno.

## Uso del token

Enviar el ID Token en cada consulta:

```bash
curl -X POST "<FUNCTION_URL>" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ID_TOKEN>" \
  -d '{"question":"que es la atencion?","session_id":"ses-1"}'
```

No enviar el Access Token: este servicio exige `token_use=id`.

## Sesiones

La clave interna se construye así:

```text
<sub-de-cognito>#<session_id-del-cliente>
```

Dos usuarios pueden utilizar el mismo identificador visible sin compartir
historial. DynamoDB guarda tambien `user_id` para auditoria.

## Respuestas

| Codigo | Condicion |
|---|---|
| `401` | Token ausente, malformado, expirado o no emitido para este User Pool/App Client |
| `400` | Token valido, pero body invalido o sin `question` |
| `200` | Consulta completada |

El cliente recibe un mensaje generico. Los detalles de validacion quedan en
CloudWatch Logs.

## Desarrollo

Los tests usan un endpoint JWKS local y claves RSA generadas en memoria; no
requieren AWS ni tokens reales.
