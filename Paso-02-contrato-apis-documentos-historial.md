# Paso 2: Contrato de APIs para documentos e historial

Fecha: 2026-08-24

## Objetivo

Definir los contratos que necesita el frontend para gestionar documentos y conversaciones sin exponer credenciales AWS ni datos de otros usuarios.

## Reglas comunes

- Todas las operaciones privadas requieren `Authorization: Bearer <ID_TOKEN>`.
- El backend obtiene el `userId` exclusivamente del `sub` del token Cognito.
- El frontend nunca envía ni decide el `userId` propietario.
- Las respuestas son JSON y usan `Content-Type: application/json`.
- Los errores no exponen excepciones, ARNs, claves ni datos internos.
- Los identificadores públicos de documento y sesión son UUID.
- Las URLs de S3 son temporales y solo se generan después de validar propiedad.

## Documentos

### Solicitar URL de subida

```text
POST /documents/upload-url
```

Request:

```json
{
  "fileName": "manual.pdf",
  "contentType": "application/pdf",
  "size": 123456
}
```

Response `200`:

```json
{
  "documentId": "uuid",
  "objectKey": "documents/user-id/uuid-manual.pdf",
  "uploadUrl": "https://s3-presigned-url"
}
```

El navegador ejecuta después un `PUT` directo a `uploadUrl` con `Content-Type: application/pdf`.

Errores:

- `400`: nombre, MIME o tamaño inválido.
- `401`: token ausente o inválido.
- `500`: no se pudo generar la URL.

### Registrar y consultar estado

```text
GET /documents
GET /documents/{documentId}
```

Response `200`:

```json
{
  "documents": [
    {
      "documentId": "uuid",
      "fileName": "manual.pdf",
      "contentType": "application/pdf",
      "size": 123456,
      "status": "READY",
      "createdAt": "2026-08-24T12:00:00Z",
      "updatedAt": "2026-08-24T12:01:00Z"
    }
  ]
}
```

Estados permitidos:

- `PENDING`: URL generada, archivo todavía no confirmado.
- `PROCESSING`: S3 disparó la Lambda de ingesta.
- `READY`: extracción e indexación completadas.
- `ERROR`: procesamiento fallido.

El endpoint individual devuelve `404` si el documento no existe o no pertenece al usuario autenticado.

### Solicitar visualización

```text
GET /documents/{documentId}/view-url
```

Response `200`:

```json
{
  "documentId": "uuid",
  "fileName": "manual.pdf",
  "viewUrl": "https://s3-presigned-get-url",
  "expiresIn": 600
}
```

La URL se debe generar con una expiración limitada y solo para un documento propiedad del usuario.

### Eliminar documento

```text
DELETE /documents/{documentId}
```

Response `204` sin body, o `200` con:

```json
{
  "documentId": "uuid",
  "deleted": true
}
```

La eliminación debe considerar el objeto S3, la metadata y los vectores de Pinecone asociados. Si la eliminación de vectores no se implementa en esta fase, debe documentarse como deuda técnica antes de exponer el botón al usuario.

## Persistencia de documentos

Se recomienda una tabla DynamoDB separada:

```text
DocumentsTable
PK: document_id
Attributes:
- user_id
- object_key
- file_name
- content_type
- size
- status
- created_at
- updated_at
- error_message (solo para uso interno o mensaje controlado)
```

Para listar eficientemente por usuario se requiere un índice secundario:

```text
GSI1PK: user_id
GSI1SK: updated_at
```

## Conversaciones

### Listar conversaciones

```text
GET /sessions
```

Response `200`:

```json
{
  "sessions": [
    {
      "sessionId": "uuid",
      "title": "Resumen del manual",
      "lastMessagePreview": "La respuesta comienza con...",
      "messageCount": 4,
      "createdAt": "2026-08-24T12:00:00Z",
      "updatedAt": "2026-08-24T12:10:00Z"
    }
  ],
  "nextToken": null
}
```

El resultado debe incluir únicamente sesiones del usuario autenticado y ordenarse por `updatedAt` descendente.

### Obtener conversación

```text
GET /sessions/{sessionId}
```

Response `200`:

```json
{
  "sessionId": "uuid",
  "title": "Resumen del manual",
  "createdAt": "2026-08-24T12:00:00Z",
  "updatedAt": "2026-08-24T12:10:00Z",
  "messages": [
    {
      "role": "user",
      "content": "¿Qué explica el manual?",
      "timestamp": "2026-08-24T12:00:00Z"
    },
    {
      "role": "assistant",
      "content": "El manual explica...",
      "timestamp": "2026-08-24T12:00:03Z"
    }
  ]
}
```

El endpoint debe devolver `404` cuando la sesión no exista o no pertenezca al usuario autenticado.

### Eliminar conversación

```text
DELETE /sessions/{sessionId}
```

Response `204` sin body.

La operación debe validar el prefijo o atributo `user_id` antes de eliminar.

## Compatibilidad con el endpoint RAG actual

El endpoint actual de consulta conserva su contrato:

```text
POST /
```

Request:

```json
{
  "question": "¿Qué explica el documento?",
  "session_id": "uuid-opcional"
}
```

Response:

```json
{
  "answer": "Respuesta con citas...",
  "sources": [],
  "session_id": "uuid",
  "model": "modelo-configurado"
}
```

No se debe modificar el flujo actual de autenticación ni el aislamiento de sesiones durante la implementación de las nuevas operaciones.

## Criterios de aceptación del backend

- Un usuario autenticado puede listar únicamente sus documentos.
- Un usuario autenticado puede obtener una URL temporal de lectura solo para sus documentos.
- Un usuario autenticado puede listar únicamente sus conversaciones.
- Un usuario no puede consultar ni eliminar recursos de otro usuario aunque conozca el UUID.
- Los estados de documentos se actualizan después del evento S3.
- Las APIs devuelven códigos HTTP consistentes.
- Existen tests unitarios para autorización, validación, respuestas exitosas y errores.
- Existe al menos una prueba E2E con Cognito y recursos AWS configurados.

## Decisión para el siguiente paso

La siguiente implementación debe comenzar por la metadata de documentos y sus endpoints de listado/estado. El visor y el historial del frontend dependerán de estos contratos.
