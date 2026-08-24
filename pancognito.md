Plan: Autenticación de Usuarios con Amazon Cognito
Descripción
Añadir autenticación JWT completa al microservicio consulta usando Amazon Cognito User Pool. El flujo actual tiene AuthType: NONE en la Lambda Function URL, por lo que cualquiera con la URL puede consultar. El objetivo es que solo usuarios registrados y autenticados puedan invocar la API.

Dado que la Lambda Function URL no admite directamente un Cognito Authorizer nativo (ese mecanismo es de API Gateway), la estrategia correcta es:

Mantener la Function URL con AuthType: NONE (CORS sigue funcionando).
Validar manualmente el JWT de Cognito dentro de QueryHandler extrayendo el Authorization: Bearer <token> del header de la petición, verificando su firma contra las claves JWKS del User Pool y extrayendo el sub (user_id) del token.
Si el token es inválido o está ausente → retornar HTTP 401 Unauthorized.
El sub del token reemplaza al UUID anónimo como identificador de sesión base (se puede combinar con un session_id enviado por el cliente para permitir múltiples sesiones por usuario).
Esta estrategia no requiere API Gateway adicional (cero costo extra de infraestructura) y es completamente serverless.

Decisiones de Diseño
IMPORTANT

No se usará API Gateway: La validación JWT se hace dentro de la Lambda para evitar costos y complejidad extra. Esto es un patrón estándar para proyectos que usan Lambda Function URL directamente.

IMPORTANT

User Pool solamente (no Identity Pool): El frontend enviará el ID Token de Cognito en cada request. No necesitamos credenciales AWS temporales en el cliente, por lo tanto no se necesita Identity Pool.

NOTE

Biblioteca de verificación JWT: Se usará com.nimbusds:nimbus-jose-jwt (la más establecida en el ecosistema Java para verificación de JWTs OIDC/Cognito). Cachea las claves JWKS entre invocaciones Lambda para evitar el cold-start de descarga de claves.

Open Questions
IMPORTANT

¿Quieres Hosted UI (página de login de Cognito) o solo la API? El plan incluye el User Pool + App Client para que un frontend pueda integrar el login. Si también quieres que el propio proyecto incluya una página de login HTML/JS, indícalo antes de ejecutar.

Proposed Changes
Infraestructura SAM (template.yaml)
[MODIFY] 
template.yaml
Añadir recurso AWS::Cognito::UserPool con política de contraseñas, verificación de email y MFA opcional.
Añadir recurso AWS::Cognito::UserPoolClient (App Client público, sin secret, flujo USER_SRP_AUTH + REFRESH_TOKEN_AUTH).
Añadir variable de entorno COGNITO_USER_POOL_ID y COGNITO_APP_CLIENT_ID a QueryFunction.
No modificar AuthType de la Function URL (se mantiene NONE).
Añadir outputs: UserPoolId, AppClientId, UserPoolDomain (para configurar el frontend).
Dependencias Maven (pom.xml)
[MODIFY] 
pom.xml
Añadir com.nimbusds:nimbus-jose-jwt:9.37.3 para verificación de tokens JWT.
Añadir net.minidev:json-smart:2.5.1 (dependencia transitiva de nimbus, declarada explícitamente para control de versión).
Código Java (nuevo archivo)
[NEW] src/main/java/com/example/CognitoJwtVerifier.java
Clase responsable de:

Descargar y cachear las claves JWKS del User Pool (https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json) al iniciar la Lambda (warm start: reutiliza el cache en memoria).
Verificar la firma del token JWT.
Validar iss (debe ser el User Pool), aud/client_id (debe ser el App Client), token_use (debe ser id), y exp (no expirado).
Retornar el sub (identificador único de usuario en Cognito) si el token es válido.
Lanzar UnauthorizedException (nueva excepción checked) si falla cualquier validación.
[NEW] src/main/java/com/example/UnauthorizedException.java
Excepción simple para marcar errores de autenticación, diferenciada de errores de validación de input (400) y errores de servidor (502).

Código Java (archivos modificados)
[MODIFY] 
QueryHandler.java
Añadir campo CognitoJwtVerifier jwtVerifier con inicialización en constructor por defecto.
Al inicio de handleRequest: extraer header authorization del evento, llamar a jwtVerifier.verify(token) para obtener el sub.
Si lanza UnauthorizedException → retornar HTTP 401 con cuerpo {"error":"No autenticado. Se requiere un token JWT de Cognito valido."}.
El sub del token se puede usar opcionalmente como prefijo del session_id ({sub}#{session_id}) para asegurar que los usuarios no accedan a sesiones ajenas.
[MODIFY] 
src/main/java/com/example/SessionStore.java
Añadir campo userId a los ítems de DynamoDB para auditoría.
Añadir el userId (sub de Cognito) al ítem de sesión en appendTurn.
Tests Unitarios
[NEW] src/test/java/com/example/CognitoJwtVerifierTest.java
Tests unitarios con tokens JWT mockeados que validan:

Token válido devuelve el sub correcto.
Token expirado lanza UnauthorizedException.
Token con iss incorrecto lanza UnauthorizedException.
Ausencia de token lanza UnauthorizedException.
[MODIFY] 
src/test/java/com/example/QueryHandlerTest.java
Añadir test de respuesta 401 cuando no hay token.
Añadir test de respuesta 401 cuando el token es inválido.
Los tests existentes (400, 200) se adaptan para pasar un mock de CognitoJwtVerifier que devuelve un sub fijo.
Documentación
[NEW] consulta/CognitoJwtVerifier.md
Documento Markdown detallando el flujo de autenticación, cómo obtener tokens (Hosted UI o SDK Amplify), formato del header Authorization, y directivas para IA.

[MODIFY] 
consulta/QueryHandler.md
Actualizar con la nueva sección de autenticación JWT y el código de estado 401.

[MODIFY] 
consulta/sam_explicacion.md
Añadir sección explicando el User Pool, App Client y sus parámetros SAM.

Verification Plan
Automated Tests
bash

cd consulta
mvn test
Los 22 tests existentes deben seguir pasando; se añaden los tests del CognitoJwtVerifier y los nuevos casos de QueryHandler.

Manual E2E Verification
sam build && sam deploy → obtener UserPoolId y AppClientId del output.
Crear un usuario de prueba:
bash

aws cognito-idp admin-create-user --user-pool-id <ID> --username test@example.com --temporary-password "Test1234!"
aws cognito-idp admin-set-user-password --user-pool-id <ID> --username test@example.com --password "Test1234!" --permanent
Obtener tokens de autenticación:
bash

aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH --client-id <CLIENT_ID> \
  --auth-parameters USERNAME=test@example.com,PASSWORD=Test1234!
Llamar a la API con y sin token para verificar respuestas 200 y 401.Plan: Autenticación de Usuarios con Amazon Cognito
Descripción
Añadir autenticación JWT completa al microservicio consulta usando Amazon Cognito User Pool. El flujo actual tiene AuthType: NONE en la Lambda Function URL, por lo que cualquiera con la URL puede consultar. El objetivo es que solo usuarios registrados y autenticados puedan invocar la API.

Dado que la Lambda Function URL no admite directamente un Cognito Authorizer nativo (ese mecanismo es de API Gateway), la estrategia correcta es:

Mantener la Function URL con AuthType: NONE (CORS sigue funcionando).
Validar manualmente el JWT de Cognito dentro de QueryHandler extrayendo el Authorization: Bearer <token> del header de la petición, verificando su firma contra las claves JWKS del User Pool y extrayendo el sub (user_id) del token.
Si el token es inválido o está ausente → retornar HTTP 401 Unauthorized.
El sub del token reemplaza al UUID anónimo como identificador de sesión base (se puede combinar con un session_id enviado por el cliente para permitir múltiples sesiones por usuario).
Esta estrategia no requiere API Gateway adicional (cero costo extra de infraestructura) y es completamente serverless.

Decisiones de Diseño
IMPORTANT

No se usará API Gateway: La validación JWT se hace dentro de la Lambda para evitar costos y complejidad extra. Esto es un patrón estándar para proyectos que usan Lambda Function URL directamente.

IMPORTANT

User Pool solamente (no Identity Pool): El frontend enviará el ID Token de Cognito en cada request. No necesitamos credenciales AWS temporales en el cliente, por lo tanto no se necesita Identity Pool.

NOTE

Biblioteca de verificación JWT: Se usará com.nimbusds:nimbus-jose-jwt (la más establecida en el ecosistema Java para verificación de JWTs OIDC/Cognito). Cachea las claves JWKS entre invocaciones Lambda para evitar el cold-start de descarga de claves.

Open Questions
IMPORTANT

¿Quieres Hosted UI (página de login de Cognito) o solo la API? El plan incluye el User Pool + App Client para que un frontend pueda integrar el login. Si también quieres que el propio proyecto incluya una página de login HTML/JS, indícalo antes de ejecutar.

Proposed Changes
Infraestructura SAM (template.yaml)
[MODIFY] 
template.yaml
Añadir recurso AWS::Cognito::UserPool con política de contraseñas, verificación de email y MFA opcional.
Añadir recurso AWS::Cognito::UserPoolClient (App Client público, sin secret, flujo USER_SRP_AUTH + REFRESH_TOKEN_AUTH).
Añadir variable de entorno COGNITO_USER_POOL_ID y COGNITO_APP_CLIENT_ID a QueryFunction.
No modificar AuthType de la Function URL (se mantiene NONE).
Añadir outputs: UserPoolId, AppClientId, UserPoolDomain (para configurar el frontend).
Dependencias Maven (pom.xml)
[MODIFY] 
pom.xml
Añadir com.nimbusds:nimbus-jose-jwt:9.37.3 para verificación de tokens JWT.
Añadir net.minidev:json-smart:2.5.1 (dependencia transitiva de nimbus, declarada explícitamente para control de versión).
Código Java (nuevo archivo)
[NEW] src/main/java/com/example/CognitoJwtVerifier.java
Clase responsable de:

Descargar y cachear las claves JWKS del User Pool (https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json) al iniciar la Lambda (warm start: reutiliza el cache en memoria).
Verificar la firma del token JWT.
Validar iss (debe ser el User Pool), aud/client_id (debe ser el App Client), token_use (debe ser id), y exp (no expirado).
Retornar el sub (identificador único de usuario en Cognito) si el token es válido.
Lanzar UnauthorizedException (nueva excepción checked) si falla cualquier validación.
[NEW] src/main/java/com/example/UnauthorizedException.java
Excepción simple para marcar errores de autenticación, diferenciada de errores de validación de input (400) y errores de servidor (502).

Código Java (archivos modificados)
[MODIFY] 
QueryHandler.java
Añadir campo CognitoJwtVerifier jwtVerifier con inicialización en constructor por defecto.
Al inicio de handleRequest: extraer header authorization del evento, llamar a jwtVerifier.verify(token) para obtener el sub.
Si lanza UnauthorizedException → retornar HTTP 401 con cuerpo {"error":"No autenticado. Se requiere un token JWT de Cognito valido."}.
El sub del token se puede usar opcionalmente como prefijo del session_id ({sub}#{session_id}) para asegurar que los usuarios no accedan a sesiones ajenas.
[MODIFY] 
src/main/java/com/example/SessionStore.java
Añadir campo userId a los ítems de DynamoDB para auditoría.
Añadir el userId (sub de Cognito) al ítem de sesión en appendTurn.
Tests Unitarios
[NEW] src/test/java/com/example/CognitoJwtVerifierTest.java
Tests unitarios con tokens JWT mockeados que validan:

Token válido devuelve el sub correcto.
Token expirado lanza UnauthorizedException.
Token con iss incorrecto lanza UnauthorizedException.
Ausencia de token lanza UnauthorizedException.
[MODIFY] 
src/test/java/com/example/QueryHandlerTest.java
Añadir test de respuesta 401 cuando no hay token.
Añadir test de respuesta 401 cuando el token es inválido.
Los tests existentes (400, 200) se adaptan para pasar un mock de CognitoJwtVerifier que devuelve un sub fijo.
Documentación
[NEW] consulta/CognitoJwtVerifier.md
Documento Markdown detallando el flujo de autenticación, cómo obtener tokens (Hosted UI o SDK Amplify), formato del header Authorization, y directivas para IA.

[MODIFY] 
consulta/QueryHandler.md
Actualizar con la nueva sección de autenticación JWT y el código de estado 401.

[MODIFY] 
consulta/sam_explicacion.md
Añadir sección explicando el User Pool, App Client y sus parámetros SAM.

Verification Plan
Automated Tests
bash

cd consulta
mvn test
Los 22 tests existentes deben seguir pasando; se añaden los tests del CognitoJwtVerifier y los nuevos casos de QueryHandler.

Manual E2E Verification
sam build && sam deploy → obtener UserPoolId y AppClientId del output.
Crear un usuario de prueba:
bash

aws cognito-idp admin-create-user --user-pool-id <ID> --username test@example.com --temporary-password "Test1234!"
aws cognito-idp admin-set-user-password --user-pool-id <ID> --username test@example.com --password "Test1234!" --permanent
Obtener tokens de autenticación:
bash

aws cognito-idp initiate-auth --auth-flow USER_PASSWORD_AUTH --client-id <CLIENT_ID> \
  --auth-parameters USERNAME=test@example.com,PASSWORD=Test1234!
Llamar a la API con y sin token para verificar respuestas 200 y 401.

 Añadir dependencias nimbus-jose-jwt a pom.xml
 Crear UnauthorizedException.java
 Crear CognitoJwtVerifier.java
 Modificar QueryHandler.java (validación JWT + HTTP 401)
 Modificar SessionStore.java (campo userId en ítem DynamoDB)
 Actualizar template.yaml (UserPool, AppClient, variables env)
 Crear CognitoJwtVerifierTest.java
 Actualizar QueryHandlerTest.java (tests 401)
 Crear CognitoJwtVerifier.md
 Actualizar QueryHandler.md y sam_explicacion.md
 mvn test → verificar BUILD SUCCESS

## Estado Final

Pancognito completado y verificado el 2026-08-23.

Checklist final:

- [x] User Pool y App Client de Cognito desplegados en `us-east-1`.
- [x] Validación JWT RS256 mediante JWKS dentro de `QueryHandler`.
- [x] Validación de `iss`, `aud`, `token_use=id`, `sub` y expiración.
- [x] Respuesta `401` para tokens ausentes o inválidos.
- [x] Sesiones aisladas mediante `{sub}#{session_id}`.
- [x] Atributo `user_id` persistido en DynamoDB.
- [x] CORS habilitado para el header `Authorization`.
- [x] Tests Maven: 30 ejecutados, 0 fallos.
- [x] `sam validate` exitoso.
- [x] `sam build` exitoso.
- [x] Despliegue CloudFormation exitoso (`UPDATE_COMPLETE`).
- [x] E2E verificado: `401` sin token, `200` con ID Token válido y sesión persistida.

Alcance no incluido: Hosted UI y una página frontend de login. El frontend debe
obtener el ID Token mediante Cognito SDK/Amplify y enviarlo como
`Authorization: Bearer <ID_TOKEN>`.
