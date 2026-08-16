Markdown
# Contexto de Arquitectura: RAG en AWS (Retrieval-Augmented Generation)

## Resumen General
Esta arquitectura describe un sistema de Generación Aumentada por Recuperación (RAG) serverless implementado en AWS. El sistema consta de dos flujos principales: el **Flujo de Ingestión e Indexación de Documentos** y el **Flujo de Consulta / Respuesta (RAG)**, apoyados por servicios auxiliares de la nube.

---

## Servicios de AWS y sus Respuestas/Funciones en la Arquitectura

### 1. Entrada y Salida (Interfaces)
* **Amazon API Gateway (HTTPS):** Punto de entrada público. Recibe las solicitudes desde la aplicación del usuario (Web o Mobile) y las enruta hacia la función Lambda.

### 2. Cómputo y Lógica Principal
* **AWS Lambda (Función de API / Procesamiento):** 
  * En las consultas: Actúa como backend, coordina la búsqueda de contexto, consulta al LLM y guarda/recupera el historial.
  * En la ingestión: Se activa por eventos de S3 para extraer texto, dividir en chunks, solicitar embeddings y estructurar metadatos.

### 3. Almacenamiento de Archivos y Documentos
* **Amazon S3 (Bucket de Documentos):** Almacena los archivos originales cargados por los usuarios (PDF, DOCX, TXT, etc.).
  * **Estructura del Bucket (`meu-rag-bucket/`):**
    * `documents/`: Archivos fuente originales (`.pdf`, `.docx`, `.txt`).
    * `processed/`: Archivos procesados.
    * `embeddings/`: Archivos de vectores/índices exportados.
    * `backups/`: Copias de seguridad y versiones.

### 4. Base de Datos Vectorial (RAG / Knowledge Base)
* **Pinecon** **: Almacena los vectores de embeddings y sus metadatos. Se utiliza para realizar las búsquedas de similitud e indexación vectorial durante la consulta RAG.

### 5. Inteligencia Artificial / Modelos Generativos (LLM & Embeddings)
* **Grok api** 
  * **Embeddings:** Genera la representación vectorial de los textos durante el proceso de ingestión.
  * **LLM (Claude, Llama, Mistral, Nova, etc.):** Procesa el contexto recuperado junto a la pregunta del usuario para generar la respuesta final.

### 6. Persistencia de Sesión e Historial
* **Amazon DynamoDB:** Base de datos NoSQL que almacena el historial de conversaciones, datos de usuarios y sesiones activas.

---

## Servicios de Apoyo y Seguridad

* **Amazon Cognito:** Gestión de identidad y autenticación de usuarios.
* **Amazon CloudWatch:** Monitoreo, centralización de logs y métricas del sistema.
* **AWS Secrets Manager:** Almacenamiento seguro de credenciales, claves API y secretos.
* **Amazon EventBridge:** Programación de tareas periódicas, eventos asíncronos y reindexación.

---

## Flujos de Trabajo Paso a Paso

### A. Flujo de Ingestión e Indexación de Documentos
1. **Carga:** Se sube un archivo (PDF, DOCX, TXT) al bucket **Amazon S3**.
2. **Evento:** S3 dispara un evento que invoca a **AWS Lambda**.
3. **Procesamiento:** Lambda extrae el texto y lo divide en fragmentos (*chunks*).
4. **Vectorización:** Lambda envía los fragmentos a **Grok api** para generar los *embeddings*.
5. **Almacenamiento Vectorial:** Los vectores y metadatos generados se almacenan en el **Banco Vectorial** (PINECON).

### B. Flujo de Consulta (RAG)
1. **Envío:** El **Usuario** realiza una pregunta desde la App Web.
2. **Recepción:** **Amazon API Gateway** recibe la solicitud HTTPS.
3. **Procesamiento:** **AWS Lambda** procesa la solicitud.
4. **Búsqueda Vectorial:** Lambda consulta la **Knowledge Base / Banco Vectorial** para recuperar los fragmentos de texto más relevantes.
5. **Generación:** Lambda envía la pregunta original + el contexto recuperado a **Grok api**.
6. **Respuesta:** Grok genera la respuesta basada en el contexto recibido y se la devuelve a Lambda.
7. **Guardado:** Lambda registra la conversación/interacción en **Amazon DynamoDB**.
8. **Devolución:** La respuesta final se entrega al usuario a través de API Gateway.