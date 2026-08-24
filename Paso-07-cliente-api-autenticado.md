# Paso 7: Cliente API autenticado

Fecha: 2026-08-24

## Objetivo

Conectar el frontend con las Function URLs del backend usando el ID Token de Cognito y contratos TypeScript compartidos.

## Implementado

- Cliente HTTP autenticado en `frontend/lib/api/client.ts`.
- Obtención del ID Token mediante `fetchAuthSession`.
- Renovación única del token cuando la API responde `401`.
- Mensajes de error controlados.
- Cliente para consulta RAG.
- Cliente para listado y visualización de documentos.
- Cliente para listado, detalle y eliminación de sesiones.
- Flujo completo de subida PDF: URL prefirmada y `PUT` directo a S3.
- Tipos TypeScript para respuestas del backend.
- Dashboard conectado para cargar conteos de sesiones y documentos.
- Logout conectado al botón de perfil.

## Variables necesarias

```env
NEXT_PUBLIC_QUERY_API_URL=
NEXT_PUBLIC_UPLOAD_API_URL=
NEXT_PUBLIC_DOCUMENTS_API_URL=
NEXT_PUBLIC_SESSIONS_API_URL=
```

Las URLs deben corresponder a los outputs de SAM desplegados.

## Seguridad

- El token se agrega automáticamente al header `Authorization`.
- No se almacenan claves privadas en el frontend.
- Las URLs de documentos se solicitan solo mediante endpoint autenticado.
- El cliente reintenta una sola vez para evitar bucles de renovación.

## Comentarios de código

Las funciones del cliente API y las funciones nuevas del dashboard tienen comentarios explicativos sobre autenticación, errores y responsabilidad.

## Verificación pendiente

No fue posible ejecutar `npm install`, `npm run lint`, `npm run typecheck` ni `npm run build` porque el entorno no dispone de Node.js/npm.

También queda pendiente probar las URLs reales de Lambda con Cognito y un usuario autenticado.

## Estado

**Implementado en código; verificación automática y E2E pendientes por disponibilidad del entorno y despliegue AWS.**
