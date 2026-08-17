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

public class PdfTextExtractor implements RequestHandler<S3Event, String> {

    private static final Logger logger = LoggerFactory.getLogger(PdfTextExtractor.class);
    private final S3Client s3Client;
    private final PineconeClient pineconeClient;

    public PdfTextExtractor() {
        // Inicializa el cliente S3 (usará las credenciales y región por defecto del entorno Lambda)
        this.s3Client = S3Client.builder().build();
        // Carga la API key desde Secrets Manager y la configuración desde variables Lambda.
        this.pineconeClient = PineconeClient.fromEnvironment();
    }

    // Constructor para testing
    public PdfTextExtractor(S3Client s3Client) {
        this(s3Client, null);
    }

    // Constructor para testing: permite simular S3 sin realizar llamadas a Pinecone.
    public PdfTextExtractor(S3Client s3Client, PineconeClient pineconeClient) {
        this.s3Client = s3Client;
        this.pineconeClient = pineconeClient;
    }

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

    private String processRecord(S3EventNotification.S3EventNotificationRecord record) {
        String srcBucket = record.getS3().getBucket().getName();

        // Reemplazar los espacios codificados por espacios reales.
        String srcKey = record.getS3().getObject().getUrlDecodedKey();
        if (srcKey == null) {
            srcKey = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);
        }

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
             PDDocument document = Loader.loadPDF(s3Object.readAllBytes())) {
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

        return "Exito. Archivo procesado: " + srcKey + ". Caracteres extraidos: "
                + extractedText.length() + ". Chunks generados: " + chunks.size();
    }
}
