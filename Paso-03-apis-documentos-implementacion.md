# Paso 3: Implementacion de APIs de documentos

Fecha: 2026-08-24

## Objetivo

Agregar metadata, estados de procesamiento y consultas seguras de documentos sin exponer credenciales AWS ni documentos de otros usuarios.

## Cambios realizados

### Persistencia

- Se agrego `DocumentStore`.
- Se agrego la tabla `DocumentsTable` en SAM.
- Se agrego el indice secundario `user-updated-index` configurable mediante `DocumentsUserIndex`.
- Se persisten `document_id`, `user_id`, nombre, tipo, tamaño, key S3, fechas y estado.

### Estados

- `PENDING`: se registra al solicitar la URL de subida.
- `PROCESSING`: se establece al recibir el evento S3.
- `READY`: se establece cuando la indexacion termina correctamente.
- `ERROR`: se establece cuando el PDF no puede procesarse o no contiene texto utilizable.

### Subida

`UploadUrlHandler` conserva el contrato anterior y ahora guarda metadata `PENDING` después de generar la URL prefirmada.

### Consulta de documentos

Se agrego `DocumentHandler` con una Function URL independiente:

```text
GET /documents
GET /documents/{documentId}
GET /documents/{documentId}/view-url
```

Todas las rutas requieren un ID Token Cognito y validan que el documento pertenezca al usuario autenticado.

### Visualizacion

`GET /documents/{documentId}/view-url` genera una URL S3 prefirmada de lectura con duración de diez minutos.

### Infraestructura

Se agregaron:

- Permisos DynamoDB para crear y actualizar metadata.
- Permisos DynamoDB `GetItem` y `Query` para consulta.
- Permiso S3 `GetObject` para visualización.
- Output `DocumentsFunctionUrl`.
- Variables `DOCUMENTS_TABLE` y `DOCUMENTS_USER_INDEX`.

## Comentarios de código

Las funciones nuevas y modificadas en `DocumentStore`, `DocumentHandler`, `UploadUrlHandler` y `PdfTextExtractor` incluyen comentarios Javadoc en español que explican su responsabilidad.

## Tests ejecutados

```bash
cd ingesta
mvn test
```

Resultado:

- `12` tests ejecutados.
- `0` fallos.
- `0` errores.
- `BUILD SUCCESS`.
- `PineconeClientIntegrationTest` omitido por falta de configuración externa.

Se agregó `DocumentHandlerTest`, que verifica HTTP `401` cuando falta el token Cognito antes de acceder a DynamoDB.

## Validaciones pendientes

- `sam validate` continúa pendiente porque AWS SAM CLI no está disponible en el entorno.
- No se hizo deploy.
- No se ejecutó E2E contra S3, DynamoDB, Cognito y Pinecone reales.
- La eliminación de documentos y vectores Pinecone queda fuera de este paso.

## Riesgo conocido

La metadata se crea antes de que el navegador ejecute el `PUT` a S3. Si el usuario abandona la subida, puede quedar un documento `PENDING`; se deberá agregar limpieza por TTL o un endpoint de cancelación en una etapa posterior.

## Estado

**Implementado y verificado localmente.** Requiere validación SAM y despliegue controlado antes de ser consumido por el frontend.
