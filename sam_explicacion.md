# Explicación de template.yaml y samconfig.toml (AWS SAM)

## template.yaml — el "mapa" de la infraestructura

`template.yaml` le dice a AWS qué recursos crear (Lambda, bucket S3, permisos, eventos). Usa AWS SAM (Serverless Application Model).

### 1. Cabecera

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31
```

Le dice a AWS que el archivo usa el estándar Serverless de AWS (SAM).

### 2. Parámetros (Parameters)

Valores que se pasan en el deploy (`--parameter-overrides`) y no están fijos en el código:

| Parámetro | Descripción |
|---|---|
| `BucketName` | Nombre del bucket S3 donde se suben los PDFs |
| `PineconeSecretArn` | ARN del secreto de Secrets Manager que contiene la API key de Pinecone |
| `PineconeIndexHost` | Host HTTPS del índice Pinecone (sin `https://` final) |
| `PineconeNamespace` | Namespace destino en el índice (default `documents-dev`) |
| `PineconeTextField` | Campo del registro que el índice usa para embedding (default `text`) |

### 3. Bucket S3 (`DocumentsBucket`)

```yaml
DocumentsBucket:
  Type: AWS::S3::Bucket
  Properties:
    BucketName: !Ref BucketName
```

Crea el bucket donde se guardan los PDFs. El nombre es global en AWS, por eso se pasa como parámetro.

### 4. La Función Lambda (`PdfTextExtractorFunction`)

```yaml
PdfTextExtractorFunction:
  Type: AWS::Serverless::Function
  Properties:
    CodeUri: .
    Handler: com.example.PdfTextExtractor::handleRequest
    Runtime: java17
    MemorySize: 1024
    Timeout: 120
```

- `CodeUri: .` — el código es el proyecto actual (Maven construye el JAR).
- `Handler` — clase y método de entrada: `PdfTextExtractor.handleRequest`.
- `Runtime: java17` — Java 17.
- `MemorySize: 1024` / `Timeout: 120` — 1 GB de RAM y hasta 2 minutos por invocación (un PDF grande con muchos chunks necesita tiempo).

#### Variables de entorno

Pasan la configuración de Pinecone sin escribir credenciales en el código:

```yaml
PINECONE_SECRET_ARN: !Ref PineconeSecretArn
PINECONE_INDEX_HOST: !Ref PineconeIndexHost
PINECONE_NAMESPACE: !Ref PineconeNamespace
PINECONE_TEXT_FIELD: !Ref PineconeTextField
```

#### Permisos (Policies)

```yaml
- s3:*, s3express:*            # leer PDFs de cualquier bucket
- secretsmanager:GetSecretValue  # SOLO sobre el ARN del secreto Pinecone
```

La Lambda solo puede leer la key de Pinecone desde el secreto indicado; no tiene acceso a otros secretos.

#### Evento S3 (trigger automático)

```yaml
Events:
  PdfCreatedEvent:
    Type: S3
    Properties:
      Bucket: !Ref DocumentsBucket
      Events: s3:ObjectCreated:*
      Filter:
        S3Key:
          Rules:
            - Name: prefix
              Value: documents/
            - Name: suffix
              Value: .pdf
```

"Despierta" a la Lambda cuando se **crea** un objeto en el bucket, **solo si**:
1. Está dentro del prefijo `documents/`.
2. Termina en `.pdf`.

Un `.jpg` o un archivo fuera de `documents/` **no** invoca la Lambda (no se gasta dinero en vano).

SAM crea automáticamente:
- La `NotificationConfiguration` del bucket (evento → Lambda).
- El permiso `lambda:InvokeFunction` para el servicio `s3.amazonaws.com`.

### 5. Salidas (Outputs)

```yaml
PdfTextExtractorFunctionArn  # ARN de la Lambda
DocumentsBucketName          # Nombre del bucket (para saber dónde subir PDFs)
```

Se ven al final del `sam deploy`.

---

## samconfig.toml — las preferencias del SAM CLI

Evita repetir argumentos en cada comando:

```toml
[default.build.parameters]
cached = true    # no recompiles lo que no cambió
parallel = true  # compila funciones en paralelo

[default.deploy.parameters]
stack_name = "pdf-extractor-lambda-stack"  # nombre del stack en CloudFormation
s3_prefix = "pdf-extractor-lambda-stack"   # carpeta del JAR en el bucket de SAM
region = "us-east-1"                       # región del deploy
confirm_changeset = false                  # aplica sin preguntar
capabilities = "CAPABILITY_IAM"            # permite crear roles/permisos IAM
resolve_s3 = true                          # crea el bucket interno de SAM si falta
```

> Nota: los parámetros del template (`BucketName`, `PineconeSecretArn`, etc.) NO van aquí; se pasan en cada deploy con `--parameter-overrides`.

---

## Diferencia entre template.yaml y samconfig.toml

| `template.yaml` | `samconfig.toml` |
|---|---|
| Define **QUÉ** crear en AWS | Define **CÓMO** ejecutar los comandos del SAM CLI |
| La "arquitectura" de la app | Las "preferencias del desarrollador" |
| Lo lee AWS CloudFormation | Lo lee el SAM CLI en tu máquina |
| Cambia cuando modificás la infraestructura | Cambia cuando ajustás cómo deployás |