# Explicación de template.yaml y samconfig.toml (AWS SAM) — Consulta RAG

## template.yaml — el "mapa" de la infraestructura

`template.yaml` define los recursos serverless necesarios para el microservicio de consulta (Lambda, Function URL, tabla DynamoDB, políticas IAM de menor privilegio y configuración CORS). Utiliza la especificación AWS SAM (Serverless Application Model).

### 1. Cabecera

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31
Description: >
  consulta-rag-lambda
  Microservicio de consulta RAG: recibe preguntas via Lambda Function URL,
  recupera contexto de Pinecone, genera la respuesta con DeepSeek via OpenRouter
  y guarda las sesiones en DynamoDB.
```

Declara el uso de las transformaciones estándar de SAM sobre CloudFormation.

---

### 2. Parámetros (Parameters)

Permite parametrizar el despliegue (`--parameter-overrides`) sin hardcodear credenciales ni URLs en el código:

| Parámetro | Tipo | Descripción | Default |
|---|---|---|---|
| `PineconeSecretArn` | String | ARN del secreto en Secrets Manager con la API Key de Pinecone | *(Obligatorio)* |
| `PineconeIndexHost` | String | URL HTTPS del índice Pinecone | *(Obligatorio)* |
| `PineconeNamespace` | String | Namespace del índice donde residen los chunks | `documents-dev` |
| `PineconeTextField` | String | Nombre del campo mapeado a embedding en el índice | `text` |
| `OpenRouterSecretArn` | String | ARN del secreto en Secrets Manager con la API Key de OpenRouter | *(Obligatorio)* |
| `OpenRouterModel` | String | Identificador del modelo LLM a utilizar | `nvidia/nemotron-3-ultra-550b-a55b:free` |
| `CorsAllowedOrigins` | String | Orígenes HTTP permitidos para CORS (separados por coma) | `*` |

---

### 3. Tabla DynamoDB de Sesiones (`SessionsTable`)

```yaml
SessionsTable:
  Type: AWS::DynamoDB::Table
  Properties:
    BillingMode: PAY_PER_REQUEST
    AttributeDefinitions:
      - AttributeName: session_id
        AttributeType: S
    KeySchema:
      - AttributeName: session_id
        KeyType: HASH
    SSESpecification:
      SSEEnabled: true
```

- **`BillingMode: PAY_PER_REQUEST`**: facturación on-demand (se paga exclusivamente por las lecturas y escrituras realizadas, costo $0 en reposo).
- **`KeySchema`**: Partition Key única `session_id` (String).
- **`SSESpecification`**: cifrado en reposo habilitado con claves administradas por AWS (SSE).

---

### 4. La Función Lambda (`QueryFunction`)

```yaml
QueryFunction:
  Type: AWS::Serverless::Function
  Properties:
    CodeUri: .
    Handler: com.example.QueryHandler::handleRequest
    Runtime: java17
    Architectures:
      - x86_64
    MemorySize: 1024
    Timeout: 60
```

- **`CodeUri: .`**: indica a SAM que construya el código desde el directorio raíz mediante Maven.
- **`Handler`**: punto de entrada `com.example.QueryHandler::handleRequest`.
- **`Runtime`**: Java 17 en arquitectura x86_64.
- **`MemorySize: 1024`**: 1024 MB de memoria RAM (provee cómputo proporcional de CPU para arranque ágil de la JVM).
- **`Timeout: 60`**: 60 segundos de tiempo límite (adecuado para soportar búsqueda vectorial + generación de texto con LLM).

#### Variables de entorno

Inyectadas directamente al runtime de la función:

```yaml
Environment:
  Variables:
    PINECONE_SECRET_ARN: !Ref PineconeSecretArn
    PINECONE_INDEX_HOST: !Ref PineconeIndexHost
    PINECONE_NAMESPACE: !Ref PineconeNamespace
    PINECONE_TEXT_FIELD: !Ref PineconeTextField
    OPENROUTER_SECRET_ARN: !Ref OpenRouterSecretArn
    OPENROUTER_MODEL: !Ref OpenRouterModel
    SESSIONS_TABLE: !Ref SessionsTable
```

#### Políticas de Seguridad IAM (Principle of Least Privilege)

La función solo tiene permisos estrictos sobre los recursos que necesita:

```yaml
Policies:
  - Statement:
      - Effect: Allow
        Action:
          - secretsmanager:GetSecretValue
        Resource:
          - !Ref PineconeSecretArn
          - !Ref OpenRouterSecretArn
      - Effect: Allow
        Action:
          - dynamodb:GetItem
          - dynamodb:PutItem
          - dynamodb:UpdateItem
        Resource: !GetAtt SessionsTable.Arn
```

- Solo puede leer los dos secretos específicos de Pinecone y OpenRouter.
- Solo puede realizar operaciones de ítem sobre la tabla `SessionsTable`.

#### Lambda Function URL (HTTPS Endpoint Público)

```yaml
FunctionUrlConfig:
  AuthType: NONE
  Cors:
    AllowOrigins: !Split [",", !Ref CorsAllowedOrigins]
    AllowMethods:
      - POST
    AllowHeaders:
      - Content-Type
```

- Proporciona un endpoint HTTPS directo administrado por AWS sin necesidad de aprovisionar un API Gateway (costo $0 por invocación).
- Configuración CORS integrada que permite invocar el servicio desde cualquier aplicación web frontend o SPA (Single Page Application).

---

### 5. Salidas (Outputs)

```yaml
Outputs:
  QueryFunctionUrl:
    Description: "URL publica del microservicio de consulta (POST /query)"
    Value: !GetAtt QueryFunctionUrl.FunctionUrl
  SessionsTableName:
    Description: "Tabla DynamoDB de sesiones"
    Value: !Ref SessionsTable
```

Al finalizar el comando `sam deploy`, SAM imprime la URL HTTPS pública generada y el nombre de la tabla DynamoDB creada.

---

## samconfig.toml — Preferencias del SAM CLI

```toml
version = 0.1

[default]
[default.build.parameters]
cached = true
parallel = true

[default.deploy.parameters]
stack_name = "consulta-rag-lambda-stack"
s3_prefix = "consulta-rag-lambda-stack"
region = "us-east-1"
confirm_changeset = false
capabilities = "CAPABILITY_IAM"
resolve_s3 = true
```

- **`cached = true` / `parallel = true`**: acelera los tiempos de compilación local.
- **`stack_name`**: nombre asignado a la pila en AWS CloudFormation.
- **`capabilities = "CAPABILITY_IAM"`**: autoriza la creación automática de roles y políticas IAM por parte de CloudFormation.
- **`resolve_s3 = true`**: crea y gestiona automáticamente el bucket de despliegue de artefactos de SAM.

---

## Comparativa entre Ingesta y Consulta

| Aspecto | Microservicio Ingesta (`ingesta/`) | Microservicio Consulta (`consulta/`) |
|---|---|---|
| **Disparador / Evento** | Amazon S3 (`s3:ObjectCreated:*` en `.pdf`) | HTTPS (Lambda Function URL, POST JSON) |
| **Operación Principal** | Extracción PDFBox + TextChunker + Upsert Pinecone | Búsqueda Pinecone + Prompt RAG + OpenRouter LLM |
| **Persistencia** | Pinecone (índice vectorial) | Pinecone (lectura) + DynamoDB (sesiones) |
| **Secretos Requeridos** | `pinecone/pdf-extractor` | `pinecone/pdf-extractor` + `openrouter/chat` |
| **Timeout Lambda** | 120 segundos | 60 segundos |
