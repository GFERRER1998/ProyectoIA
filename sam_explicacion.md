# Explicación del Archivo template.yaml (AWS SAM)

El archivo `template.yaml` es el "mapa" de la infraestructura que le dice a AWS exactamente qué recursos debe crear en la nube para que tu función Lambda pueda existir y operar. Está escrito en un formato llamado AWS SAM (Serverless Application Model).

Aquí tienes la explicación en lenguaje humano de cada parte de ese archivo:

## 1. La cabecera
```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31
Description: Función Lambda para extraer texto de archivos PDF
```
Esto le dice a AWS: "Oye, este archivo usa el estándar Serverless de AWS (SAM). Trátalo como una aplicación Serverless".

## 2. Los Recursos (Resources)
Aquí es donde definimos lo que queremos crear. En este caso, queremos crear nuestra función Lambda.

### La Función (`PdfTextExtractorFunction`)
```yaml
PdfTextExtractorFunction:
  Type: AWS::Serverless::Function
```
"Quiero crear una función Lambda sin servidor."

### Propiedades de la Función (`Properties`)
- **`CodeUri: target/pdf-extractor-lambda-1.0.0.jar`**: "El código de esta función está dentro de este archivo JAR (que es lo que generamos al compilar con Maven)."
- **`Handler: com.example.PdfTextExtractor::handleRequest`**: "Cuando la Lambda se despierte, quiero que ejecutes específicamente esta clase de Java que escribimos."
- **`Runtime: java21`**: "Esta función corre en un entorno de Java 21."
- **`MemorySize: 512`**: "Dale 512 Megabytes de RAM a esta función. Extraer texto de PDFs puede consumir un poco de memoria, 512MB es un buen balance."
- **`Timeout: 30`**: "Si la función tarda más de 30 segundos en procesar un PDF, asume que falló y detenla."

### Permisos de Seguridad (`Policies`)
```yaml
Policies:
  - S3ReadPolicy:
      BucketName: bucketdedocumentos--use2-az1--x-s3
```
"Por seguridad, las funciones Lambda no pueden leer nada por defecto. Dale permiso a esta función específicamente para poder **LEER** archivos desde el bucket `bucketdedocumentos--use2-az1--x-s3`. Sin esto, el código fallaría con 'Acceso Denegado'."

### Disparadores / Eventos (`Events`)
```yaml
Events:
  S3PdfUpload:
    Type: S3
    Properties:
      Bucket: !Ref DocumentBucket
      Events: s3:ObjectCreated:*
      Filter:
        S3Key:
          Rules:
            - Name: prefix
              Value: "archivos pdf/"
            - Name: suffix
              Value: ".pdf"
```
"Esta es la regla que 'despierta' a la función. Le decimos a AWS que vigile el bucket. **Solo despierta a esta función SI:**
1. Alguien crea/sube un nuevo archivo (`s3:ObjectCreated:*`).
2. Ese archivo se subió dentro de la carpeta `archivos pdf/` (esto es el `prefix`).
3. Ese archivo termina en `.pdf` (esto es el `suffix`)."

De esta forma nos aseguramos que si alguien sube un archivo `.jpg` o `.docx`, la función NO se ejecute para no gastar dinero en vano.

---

### ¿Cómo usarás esto en el futuro?
Cuando llegue el momento de enviar los textos a **Pinecone** (la base de datos de Embeddings), vendrás a este archivo `template.yaml` y agregarás:
1. Una nueva "Policy" (Permiso) si necesitas guardar secretos para conectarte a Pinecone (por ejemplo, leer claves de AWS Secrets Manager).
2. "Environment Variables" (Variables de Entorno) para guardar allí la URL de Pinecone o la clave del API, de manera que no tengas que poner contraseñas escritas directamente en tu código Java.

---

# Explicación del Archivo samconfig.toml (AWS SAM)

El archivo `samconfig.toml` es el \"archivo de preferencias guardadas\" del SAM CLI. En vez de tener que escribir un montón de parámetros cada vez que ejecutás un comando (`sam build`, `sam deploy`, etc.), los guardás acá una sola vez y SAM los usa automáticamente.

Aquí tenés la explicación de cada parte:

## 1. La versión
```toml
version = 0.1
```
Solo indica la versión del formato del archivo. No te preocupes por esto, es fijo.

## 2. Configuración del Build (`[default.build.parameters]`)
```toml
[default.build.parameters]
cached = true
parallel = true
```
Estas son las opciones que se aplican cuando ejecutás **`sam build`**:

- **`cached = true`**: \"Guardá en caché las partes del proyecto que no cambiaron. Si ya compilé la función Lambda y no modifiqué el código, no la recompiles de cero. Ahorrá tiempo.\" (Es la carpeta `cache/` dentro de `.aws-sam`)
- **`parallel = true`**: \"Si el proyecto tuviera múltiples funciones Lambda, compilalas todas al mismo tiempo en paralelo, no una por una.\" Por ahora solo tenemos una función, pero es una buena práctica tenerlo activado.

## 3. Configuración del Deploy (`[default.deploy.parameters]`)
```toml
[default.deploy.parameters]
stack_name = "pdf-extractor-lambda-stack"
s3_prefix = "pdf-extractor-lambda-stack"
region = "us-east-2"
confirm_changeset = false
capabilities = "CAPABILITY_IAM"
resolve_s3 = true
```
Estas son las opciones que se aplican cuando ejecutás **`sam deploy`** (el comando que sube todo a AWS):

- **`stack_name = "pdf-extractor-lambda-stack"`**: \"El nombre que va a tener tu aplicación en AWS CloudFormation. Es como el nombre de la carpeta donde AWS agrupa todos los recursos que creaste (la Lambda, los permisos, etc.).\". Si vas a la consola de AWS CloudFormation, lo vas a ver con ese nombre.
- **`s3_prefix = "pdf-extractor-lambda-stack"`**: \"Cuando SAM suba el archivo `.jar` compilado a S3 (para que AWS lo use), guardalo dentro de una sub-carpeta con este nombre.\". Ayuda a mantener ordenado el bucket interno de SAM.
- **`region = "us-east-2"`**: \"Deployá todo en la región de AWS Ohio (us-east-2). Tu Lambda vivirá en esa región.\".
- **`confirm_changeset = false`**: \"No me preguntes '¿Estás seguro?' antes de aplicar los cambios. Aplicálos directamente.\". Si fuera `true`, SAM te mostraría un resumen de los cambios y esperaría tu confirmación manual.
- **`capabilities = "CAPABILITY_IAM"`**: \"Te doy permiso explícito para que crees roles y permisos de IAM durante el deploy.\". Sin esto, AWS rechaza el deploy porque crear permisos es una acción sensible.
- **`resolve_s3 = true`**: \"Creá automáticamente un bucket de S3 (si no existe) para guardar el código compilado de la Lambda durante el deploy. No me hagas crearlo a mano.\".

---

### ¿Cuál es la diferencia entre `template.yaml` y `samconfig.toml`?

| `template.yaml` | `samconfig.toml` |
|---|---|
| Define **QUÉ** crear en AWS (la Lambda, los permisos, los eventos) | Define **CÓMO** ejecutar los comandos del SAM CLI |
| Es la \"arquitectura\" de tu app | Son las \"preferencias del desarrollador\" |
| Lo lee AWS CloudFormation | Lo lee el SAM CLI en tu máquina |
| Cambia cuando modificás la infraestructura | Cambia cuando querés ajustar cómo deployás |
