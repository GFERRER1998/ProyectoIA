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

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

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

            // Configurar el request para obtener el objeto de S3
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(srcBucket)
                    .key(srcKey)
                    .build();

            logger.info("Descargando PDF desde S3...");
            // Extraer el texto del PDF
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
            
            // TODO: En el futuro, aquí es donde conectaríamos con Pinecone.
            // Para transformar `extractedText` en Embeddings y subirlos.
            // Por ahora solo retornamos/logueamos el string.

            logger.debug("Texto extraído (preview): {}", 
                extractedText.length() > 200 ? extractedText.substring(0, 200) + "..." : extractedText);

            return "Exito. Archivo procesado: " + srcKey + ". Caracteres extraidos: " + extractedText.length();

        } catch (Exception e) {
            logger.error("Error global procesando el evento de S3", e);
            throw new RuntimeException(e);
        }
    }
}
