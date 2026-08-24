package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Punto de entrada del pipeline de ingestión de documentos en AWS Lambda.
 *
 * <p>Esta función es invocada automáticamente ante eventos {@code s3:ObjectCreated:*} en el bucket
 * de documentos (bajo el prefijo {@code documents/} y con extensión {@code .pdf}).</p>
 *
 * <p>Orquesta el siguiente flujo:
 * <ol>
 *   <li>Descarga el archivo PDF desde el bucket S3 de origen.</li>
 *   <li>Extrae el contenido textual utilizando Apache PDFBox.</li>
 *   <li>Normaliza y fragmenta el texto en chunks de tamaño controlado ({@link TextChunker}).</li>
 *   <li>Envía los chunks resultantes con metadatos al índice Pinecone ({@link PineconeClient}).</li>
 * </ol>
 * </p>
 */
public class PdfTextExtractor implements RequestHandler<S3Event, String> {

    private static final Logger logger = LoggerFactory.getLogger(PdfTextExtractor.class);
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private final S3Client s3Client;
    private final PineconeClient pineconeClient;
    private final DocumentStore documentStore;

    /**
     * Constructor por defecto para ejecución en AWS Lambda en producción.
     * Inicializa el cliente S3 con credenciales de entorno y Pinecone desde Secrets Manager.
     */
    public PdfTextExtractor() {
        this.s3Client = S3Client.builder().build();
        this.pineconeClient = PineconeClient.fromEnvironment();
        this.documentStore = DocumentStore.fromEnvironment();
    }

    /**
     * Constructor para testing con cliente S3 personalizado.
     *
     * @param s3Client Cliente S3 simulado o configurado para pruebas.
     */
    public PdfTextExtractor(S3Client s3Client) {
        this(s3Client, null, null);
    }

    /**
     * Constructor para testing con clientes S3 y Pinecone inyectados.
     *
     * @param s3Client Cliente S3 para descarga.
     * @param pineconeClient Cliente Pinecone para persistencia.
     */
    public PdfTextExtractor(S3Client s3Client, PineconeClient pineconeClient) {
        this(s3Client, pineconeClient, null);
    }

    /** Constructor para pruebas que permite controlar la persistencia de estados. */
    public PdfTextExtractor(S3Client s3Client, PineconeClient pineconeClient,
                            DocumentStore documentStore) {
        this.s3Client = s3Client;
        this.pineconeClient = pineconeClient;
        this.documentStore = documentStore;
    }

    /**
     * Manejador del evento S3 entregado por AWS Lambda.
     *
     * @param s3event Evento de notificación de S3 con los registros de archivos creados.
     * @param context Contexto de ejecución de Lambda.
     * @return Resumen del procesamiento de los archivos.
     */
    @Override
    public String handleRequest(S3Event s3event, Context context) {
        // Punto de entrada de Lambda: S3 entrega el bucket y la clave del PDF.
        logger.info("Iniciando procesamiento de evento S3...");
        
        try {
            if (s3event == null || s3event.getRecords() == null || s3event.getRecords().isEmpty()) {
                logger.warn("El evento S3 no contiene registros para procesar.");
                return "Evento S3 vacio";
            }

            // Un evento S3 puede traer varios archivos: procesamos todos y no solo records[0].
            List<String> results = new ArrayList<>();
            for (S3EventNotification.S3EventNotificationRecord record : s3event.getRecords()) {
                results.add(processRecord(record));
            }
            return String.join(" | ", results);

        } catch (Exception e) {
            logger.error("Error global procesando el evento de S3", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Procesa un registro individual de notificación de evento S3:
     * valida la extensión del archivo, descarga el PDF, extrae texto con PDFBox,
     * ejecuta el chunking y persiste los vectores en Pinecone.
     *
     * @param record Registro individual de evento S3.
     * @return Mensaje de estado del procesamiento del archivo.
     */
    private String processRecord(S3EventNotification.S3EventNotificationRecord record) {
        String srcBucket = record.getS3().getBucket().getName();

        // Reemplazar los espacios codificados por espacios reales.
        String srcKey = record.getS3().getObject().getUrlDecodedKey();
        if (srcKey == null) {
            srcKey = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);
        }

        String documentId = documentIdFromKey(srcKey);
        updateStatus(documentId, DocumentStore.Status.PROCESSING, null);
        try {
            return processRecordInternal(record, srcBucket, srcKey, documentId);
        } catch (RuntimeException e) {
            updateStatus(documentId, DocumentStore.Status.ERROR, "No se pudo procesar el documento");
            throw e;
        }
    }

    /** Procesa un registro ya identificado y actualiza el estado exitoso al terminar. */
    private String processRecordInternal(S3EventNotification.S3EventNotificationRecord record,
                                         String srcBucket, String srcKey, String documentId) {
        logger.info("Recibido archivo: {} del bucket: {}", srcKey, srcBucket);
        if (!srcKey.toLowerCase().endsWith(".pdf")) {
            logger.warn("El archivo {} no es un PDF. Saltando procesamiento.", srcKey);
            return "No es PDF: " + srcKey;
        }

        // La versión 3.11.4 del evento expone el ETag como geteTag().
        String etag = record.getS3().getObject().geteTag();
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(srcBucket)
                .key(srcKey)
                .build();

        logger.info("Descargando PDF desde S3...");
        String extractedText;
        try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);
             PDDocument document = loadPdfWithinLimit(s3Object)) {
            logger.info("PDF descargado correctamente. Extrayendo texto...");
            extractedText = new PDFTextStripper().getText(document);
        } catch (Exception e) {
            logger.error("Error al procesar el archivo PDF de S3", e);
            throw new RuntimeException(e);
        }

        logger.info("Extracción completada. Longitud del texto: {} caracteres.", extractedText.length());

        // Normalizar, dividir y enviar los chunks a Pinecone.
        TextChunker.ChunkingResult chunking = TextChunker.process(extractedText);
        List<String> chunks = chunking.chunks();
        if (chunks.isEmpty()) {
            logger.warn("No se extrajo texto utilizable del archivo: {}", srcKey);
            updateStatus(documentId, DocumentStore.Status.ERROR, "El PDF no contiene texto utilizable");
            return "Sin texto extraible: " + srcKey;
        }

        logger.info("Normalización y chunking completados. Chunks: {}, tokens estimados por chunk: {}-{}",
                chunks.size(), chunking.minEstimatedTokens(), chunking.maxEstimatedTokens());
        logger.debug("Chunks preview: {}", chunks.subList(0, Math.min(2, chunks.size())));

        if (pineconeClient == null) {
            // Solo ocurre en tests que usan el constructor sin cliente externo.
            logger.warn("PineconeClient no configurado; se omite upsert de prueba.");
        } else {
            // Si Pinecone falla, la excepción sube hasta Lambda y S3 puede reintentar el evento.
            pineconeClient.upsertChunks(srcBucket, srcKey, etag, "application/pdf", chunks);
        }

        updateStatus(documentId, DocumentStore.Status.READY, null);
        return "Exito. Archivo procesado: " + srcKey + ". Caracteres extraidos: "
                + extractedText.length() + ". Chunks generados: " + chunks.size();
    }

    /** Rechaza objetos mayores al límite antes de materializarlos completamente en memoria. */
    private static PDDocument loadPdfWithinLimit(ResponseInputStream<GetObjectResponse> s3Object)
            throws java.io.IOException {
        Long contentLength = s3Object.response().contentLength();
        if (contentLength != null && contentLength > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("El PDF supera el limite de 50 MB");
        }
        return Loader.loadPDF(s3Object.readAllBytes());
    }

    /** Extrae el UUID de documento incluido al inicio del nombre de objeto S3. */
    static String documentIdFromKey(String objectKey) {
        String[] segments = objectKey.split("/");
        if (segments.length < 3 || segments[2].length() < 37) return null;
        String candidate = segments[2].substring(0, 36);
        if (segments[2].charAt(36) != '-' || !isUuid(candidate)) return null;
        return candidate;
    }

    /** Comprueba el formato UUID canónico usado por las claves de subida. */
    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Actualiza DynamoDB sin ocultar el resultado del procesamiento principal. */
    private void updateStatus(String documentId, DocumentStore.Status status, String error) {
        if (documentStore != null && documentId != null) {
            try {
                documentStore.updateStatus(documentId, status, error);
            } catch (RuntimeException e) {
                logger.error("No se pudo actualizar el estado del documento {} a {}", documentId, status, e);
            }
        }
    }
}
