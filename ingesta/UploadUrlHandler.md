# UploadUrlHandler

## Responsabilidad

`UploadUrlHandler` es la API HTTP que permite al frontend solicitar una URL
S3 prefirmada para subir un PDF sin exponer credenciales AWS.

## Flujo

1. Lee `Authorization: Bearer <ID_TOKEN>`.
2. Valida el token con `CognitoJwtVerifier`.
3. Valida nombre, tipo MIME y tamaño del PDF.
4. Genera una key privada:

```text
documents/{userId}/{documentId}-{fileName}.pdf
```

5. Devuelve una URL PUT válida durante diez minutos.

## Request

```json
{
  "fileName": "manual.pdf",
  "contentType": "application/pdf",
  "size": 123456
}
```

## Response

```json
{
  "uploadUrl": "https://...",
  "objectKey": "documents/user-id/document-id-manual.pdf",
  "documentId": "uuid"
}
```

El navegador debe ejecutar un `PUT` a `uploadUrl` con
`Content-Type: application/pdf`. Al finalizar, S3 dispara la Lambda de
extracción existente.

## Restricciones

- Solo acepta `.pdf`.
- Solo acepta `application/pdf`.
- Tamaño máximo: 50 MB.
- La URL expira en diez minutos.
- El prefijo S3 incluye el `sub` de Cognito.
