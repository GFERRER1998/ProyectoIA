Estado Actual
El proyecto tiene implementados:
- Backend Java/SAM para ingesta y consulta RAG.
- Cognito, DynamoDB, Pinecone y OpenRouter.
- Frontend Next.js con autenticación, chat, documentos e historial.
- Tests unitarios Maven.
Pero todavía no está validado end-to-end.
Bloqueadores Prioritarios
 1. ingesta/template.yaml tiene Outputs mal indentado dentro de Resources; probablemente impide sam validate y el despliegue.
 2. Existen claves reales en archivos .env; deben revocarse y rotarse inmediatamente.
 3. Los tests reales contra Pinecone/OpenRouter están omitidos.
 4. No se ha probado el frontend con lint, typecheck ni build.
 5. No se ha probado el flujo completo Cognito → subida S3 → procesamiento → Pinecone → consulta → historial.
 6. La subida valida datos declarados por el cliente, pero no impone realmente tamaño ni contenido PDF en S3.
 7. Los permisos IAM de la Lambda de ingesta son excesivamente amplios (s3:*, s3express:*, Resource: "*").
 8. Falta eliminación completa de documentos de S3, DynamoDB y Pinecone.
 9. SessionStore puede perder mensajes si hay consultas concurrentes.
10. PineconeSearchClient usa siempre "text" al leer resultados, ignorando PINECONE_TEXT_FIELD.
Plan De Implementación
1. Higiene y seguridad
- Rotar las claves expuestas.
- Revisar historial Git y logs.
- Reforzar .gitignore para .env, .env.local y variantes.
- Mantener solo archivos .env.example sin secretos.
2. Corrección de infraestructura
- Arreglar ingesta/template.yaml.
- Validar ambos templates con sam validate.
- Reducir permisos IAM al bucket y prefijo documents/*.
- Configurar CORS con el origen real del frontend.
- Confirmar nombres de tablas, índices secundarios, región y outputs.
3. Estabilización backend
- Validar método HTTP, rutas y tamaños máximos.
- Validar session_id y document_id como UUID.
- Corregir el campo configurable de Pinecone.
- Mejorar manejo de errores de estados PENDING, PROCESSING, READY y ERROR.
- Evitar pérdida de sesiones mediante actualización atómica o versionado optimista.
- Limitar el tamaño de preguntas, respuestas e ítems DynamoDB.
4. Flujo de documentos
- Impedir subidas que excedan 50 MB realmente.
- Validar tipo y contenido PDF antes de procesarlo.
- Añadir polling de estados en el frontend.
- Implementar eliminación integral de documentos.
- Verificar que usuario A no pueda acceder a documentos de usuario B.
5. Frontend
- Confirmar variables de entorno con outputs reales de SAM.
- Ejecutar:
npm install
npm run lint
npm run typecheck
npm run build
- Probar login, confirmación, refresh de token, logout, chat, documentos e historial.
6. Pruebas end-to-end
- Desplegar primero consulta, luego ingesta.
- Crear dos usuarios Cognito.
- Verificar estados y chunks en Pinecone.
- Ejecutar consultas autenticadas.
- Confirmar citas, sesiones, aislamiento y eliminación.
- Probar errores 400, 401, 404, 405 y 502.
Criterio De Finalización
El proyecto se considerará operativo cuando:
- Ambos sam validate sean exitosos.
- Ambos mvn clean test sean exitosos.
- Frontend pase lint, tipos y build.
- El flujo AWS completo funcione con usuarios reales.
- No existan secretos expuestos.
- Los usuarios estén aislados correctamente.
- Subida, consulta, historial y eliminación funcionen desde la interfaz.
