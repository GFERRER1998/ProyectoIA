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
import java.util.List;

public class PdfTextExtractor implements RequestHandler<S3Event, String> {

    private static final Logger logger = LoggerFactory.getLogger(PdfTextExtractor.class);
    private final S3Client s3Client;

    public PdfTextExtractor() {
        // Inicializa el cliente S3 (usará las credenciales y región por defecto del entorno Lambda)
        this.s3Client = S3Client.builder().build();
    }

    // Constructor para testing
    public PdfTextExtractor(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public String handleRequest(S3Event s3event, Context context) {
        // Punto de entrada de Lambda: S3 entrega el bucket y la clave del PDF.
        logger.info("Iniciando procesamiento de evento S3...");
        
        try {
            // Un evento puede tener múltiples registros, normalmente tomamos el primero
            S3EventNotification.S3EventNotificationRecord record = s3event.getRecords().get(0);

            String srcBucket = record.getS3().getBucket().getName();
            
            // Reemplazar los espacios codificados por espacios reales
            String srcKey = record.getS3().getObject().getUrlDecodedKey();
            if (srcKey == null) {
                // Si getUrlDecodedKey() no está disponible (dependiendo de la versión), hacemos un fallback
                srcKey = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8.name());
            }

            logger.info("Recibido archivo: {} del bucket: {}", srcKey, srcBucket);

            // Validar que sea un PDF (opcional pero buena práctica)
            if (!srcKey.toLowerCase().endsWith(".pdf")) {
                logger.warn("El archivo {} no es un PDF. Saltando procesamiento.", srcKey);
                return "Not a PDF";
            }

            // Paso 1: configurar el request para obtener el objeto de S3.
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(srcBucket)
                    .key(srcKey)
                    .build();

            logger.info("Descargando PDF desde S3...");
            // Paso 2: descargar el PDF y extraer su capa de texto con PDFBox.
            String extractedText = "";
            try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);
                 PDDocument document = Loader.loadPDF(s3Object.readAllBytes())) {
                
                logger.info("PDF descargado correctamente. Extrayendo texto...");
                PDFTextStripper pdfStripper = new PDFTextStripper();
                extractedText = pdfStripper.getText(document);
                
            } catch (Exception e) {
                logger.error("Error al procesar el archivo PDF de S3", e);
                throw new RuntimeException(e);
            }

            logger.info("Extracción completada. Longitud del texto: {} caracteres.", extractedText.length());

            // Paso 3: normalizar el texto y dividirlo en chunks por tokens estimados.
            // Todavía no se llama a ninguna API externa; esta es la salida de esta fase.
            TextChunker.ChunkingResult chunking = TextChunker.process(extractedText);
            List<String> chunks = chunking.chunks();
            if (chunks.isEmpty()) {
                // Un PDF escaneado normalmente no tiene capa de texto; se tratará
                // en una fase posterior con OCR si fuese necesario.
                logger.warn("No se extrajo texto utilizable del archivo: {}", srcKey);
                return "Sin texto extraible. Archivo: " + srcKey;
            }

            // Paso 4: registrar métricas para verificar el resultado en CloudWatch.
            logger.info("Normalización y chunking completados. Chunks: {}, tokens estimados por chunk: {}-{}",
                    chunks.size(), chunking.minEstimatedTokens(), chunking.maxEstimatedTokens());

            logger.debug("Texto extraído (preview): {}", 
                extractedText.length() > 200 ? extractedText.substring(0, 200) + "..." : extractedText);
            logger.debug("Chunks preview: {}", chunks.subList(0, Math.min(2, chunks.size())));

            // Paso 5: devolver un resumen. Más adelante, aquí se conectarán
            // embeddings y Pinecone sin cambiar la extracción del PDF.
            return "Exito. Archivo procesado: " + srcKey + ". Caracteres extraidos: "
                    + extractedText.length() + ". Chunks generados: " + chunks.size();

        } catch (Exception e) {
            logger.error("Error global procesando el evento de S3", e);
            throw new RuntimeException(e);
        }
    }
}
