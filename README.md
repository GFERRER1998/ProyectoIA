# ProyectoIA — RAG Serverless en AWS (Monorepo)

Sistema de Generación Aumentada por Recuperación (RAG) serverless en AWS, dividido en dos microservicios independientes:

```
ProyectoIA/
├── ingesta/    ← Ingestión e indexación de documentos (IMPLEMENTADO)
└── consulta/   ← Consulta RAG con LLM (PENDIENTE)
```

## Microservicios

| Microservicio | Estado | Stack | Descripción |
|---|---|---|---|
| `ingesta/` | Implementado y verificado | S3 → Lambda (Java 17) → Pinecone | Convierte PDFs en vectores semánticos (chunks con embedding integrado de Pinecone) |
| `consulta/` | Pendiente | Lambda (Java 17) → Pinecone + OpenRouter + DynamoDB | Consultas RAG: búsqueda vectorial, contexto a LLM, sesiones de usuario |

## Comandos

Cada microservicio es un proyecto SAM/Maven independiente: todos los comandos se ejecutan desde su propia carpeta.

```bash
cd ingesta
mvn test                      # tests unitarios + integración
sam build && sam deploy ...   # despliegue del stack de ingesta
```

## Documentación

| Documento | Contenido |
|---|---|
| `ingesta/README.md` | Detalle completo del flujo de ingesta (PDFs, Pinecone, secretos) |
| `ingesta/ContextIA.md` | Arquitectura general del sistema (fases actuales y futuras) |
| `consulta/README.md` | Microservicio de consulta (pendiente) |