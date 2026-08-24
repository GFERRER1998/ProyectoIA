# Paso 6: Autenticacion Cognito en frontend

Fecha: 2026-08-24

## Objetivo

Conectar el frontend con el User Pool de Amazon Cognito para registro, confirmación de email, login y logout.

## Implementado

- Dependencia `aws-amplify`.
- Configuración de Amplify mediante `NEXT_PUBLIC_COGNITO_USER_POOL_ID` y `NEXT_PUBLIC_COGNITO_CLIENT_ID`.
- Configuración única para evitar reconfiguraciones.
- `AuthProvider` global en el layout.
- Estados `loading`, `authenticated`, `unauthenticated` y `unconfigured`.
- Consulta de usuario actual y sesión Cognito.
- Logout con `signOut`.
- Registro con email y contraseña.
- Confirmación de email mediante código.
- Login con email y contraseña.
- Mensajes de error controlados sin exponer detalles del backend.
- Rutas:

```text
/login
/register
/confirm?email=usuario@example.com
```

## Archivos principales

- `frontend/lib/auth/config.ts`
- `frontend/lib/auth/amplify.ts`
- `frontend/lib/auth/actions.ts`
- `frontend/lib/auth/types.ts`
- `frontend/components/auth/AuthProvider.tsx`
- `frontend/app/login/page.tsx`
- `frontend/app/register/page.tsx`
- `frontend/app/confirm/page.tsx`

## Seguridad

- Las variables `NEXT_PUBLIC_*` contienen identificadores públicos, no secretos.
- No se agregaron claves AWS, Pinecone ni OpenRouter al frontend.
- La sesión y los tokens son administrados por Amplify.
- La validación definitiva de autorización continúa ocurriendo en las Lambdas mediante Cognito JWT.

## Comentarios de código

Las funciones creadas para configurar Amplify, consultar sesión, cerrar sesión, registrar, confirmar y autenticar usuarios tienen comentarios explicativos.

## Verificación

No fue posible ejecutar:

```bash
npm install
npm run lint
npm run typecheck
npm run build
```

El entorno no tiene `node` ni `npm` disponibles en el `PATH`.

También queda pendiente la prueba real contra Cognito con un User Pool configurado.

## Estado

**Implementado en código; verificación automática pendiente por falta de Node.js/npm.**
