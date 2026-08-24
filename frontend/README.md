# Frontend Proyecto IA

Aplicación Next.js con TypeScript preparada para desplegarse en Vercel.

## Requisitos

- Node.js 20 o superior.
- npm 10 o superior.

## Desarrollo

```bash
npm install
copy .env.example .env.local
npm run dev
```

## Verificación

```bash
npm run lint
npm run typecheck
npm run build
```

## Variables de entorno

Consultar `.env.example`. Las variables `NEXT_PUBLIC_*` solo contienen identificadores y URLs públicas; nunca deben contener claves privadas de AWS, Pinecone u OpenRouter.
