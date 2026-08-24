# Paso 8: UI de chat y documentos

Fecha: 2026-08-24

## Objetivo

Construir las primeras pantallas funcionales conectadas a las APIs autenticadas: chat RAG y gestión de PDFs.

## Implementado

### Chat

- Ruta `/chat`.
- Transcript de mensajes de usuario y asistente.
- Persistencia del `session_id` durante la conversación.
- Envío de preguntas al endpoint RAG.
- Estado de carga.
- Errores visibles y controlados.
- Fuentes asociadas a cada respuesta.
- Nueva conversación.
- Atajo `Ctrl + Enter` o `Command + Enter`.

### Documentos

- Ruta `/documents`.
- Drag and drop nativo.
- Selector de archivos.
- Validación de PDF y límite de 50 MB.
- Subida mediante URL prefirmada y `PUT` directo a S3.
- Listado de documentos.
- Estados `PENDING`, `PROCESSING`, `READY` y `ERROR`.
- Visualización mediante URL temporal.

### Navegación

- El dashboard enlaza a chat y documentos.
- El botón `Nueva conversación` abre `/chat`.
- Rutas no autenticadas muestran enlace al login.

## Archivos principales

- `frontend/components/chat/ChatPanel.tsx`
- `frontend/app/chat/page.tsx`
- `frontend/components/documents/DocumentPanel.tsx`
- `frontend/app/documents/page.tsx`
- `frontend/app/page.tsx`

## Comentarios de código

Las funciones de envío, validación, drag and drop, subida, actualización de documentos y visualización tienen comentarios explicativos.

## Verificación pendiente

No fue posible ejecutar `npm install`, `lint`, `typecheck` o `build` porque Node.js/npm no están instalados en el entorno actual.

La prueba E2E requiere URLs AWS desplegadas, Cognito configurado y un usuario real autenticado.

## Estado

**UI implementada; verificación automática y E2E pendientes.**
