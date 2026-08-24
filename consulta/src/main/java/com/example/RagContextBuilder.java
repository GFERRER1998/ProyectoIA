package com.example;

import com.example.DeepSeekClient.ChatMessage;
import com.example.PineconeSearchClient.SearchHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Constructor del prompt y contexto del flujo RAG (Retrieval-Augmented Generation).
 *
 * <p>Esta clase encapsula la lógica pura para transformar los fragmentos recuperados
 * desde Pinecone en un formato de prompt comprensible para el LLM. Aplica directivas estrictas
 * del sistema diseñadas para mitigar alucinaciones y forzar la citación explícita de fuentes.</p>
 *
 * <p>Al ser una clase sin dependencias externas ni llamadas de red, es completamente determinista
 * y de fácil validación unitaria.</p>
 */
public final class RagContextBuilder {

    /**
     * Plantilla del prompt del sistema (System Prompt) inyectada al LLM.
     * Instruye al modelo a responder única y exclusivamente con la información del contexto provisto,
     * citando el nombre del archivo y número de chunk correspondiente entre corchetes.
     */
    static final String SYSTEM_PROMPT = """
            Eres un asistente RAG que responde preguntas usando exclusivamente el contexto proporcionado.
            Reglas:
            1. Responde solo con informacion que este en el contexto. Si el contexto no la contiene, di que no tienes esa informacion.
            2. Al final de cada afirmacion apoyada en una fuente, cita la fuente entre corchetes, por ejemplo [documents/x.pdf (chunk 3)].
            3. Responde en el idioma de la pregunta.
            4. No inventes datos, cifras ni fuentes.

            Contexto:
            %s
            """;

    /**
     * Constructor privado para prevenir instanciación de clase utilitaria.
     */
    private RagContextBuilder() {
    }

    /**
     * Ensambla la secuencia completa de mensajes para enviar al LLM:
     * <ol>
     *   <li>Mensaje {@code system} con las directivas y los fragmentos de contexto formateados.</li>
     *   <li>Mensajes previos de la sesión (historial de turnos anteriores), si existen.</li>
     *   <li>Mensaje {@code user} con la pregunta actual del usuario.</li>
     * </ol>
     *
     * @param question Pregunta formulada por el usuario.
     * @param hits Lista de fragmentos recuperados desde la base vectorial.
     * @param history Historial de conversación previo o {@code null} si es una sesión nueva.
     * @return Lista de objetos {@link ChatMessage} listos para ser consumidos por el cliente LLM.
     */
    public static List<ChatMessage> buildMessages(String question, List<SearchHit> hits,
                                                  List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT.formatted(buildContextBlock(hits))));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));
        return messages;
    }

    /**
     * Construye el bloque de texto del contexto para incrustar en el System Prompt.
     * Enumera cada fragmento como {@code [Fuente N] <source_key> (chunk <index>)} seguido de su texto.
     *
     * @param hits Lista de fragmentos recuperados.
     * @return Cadena formateada con el contexto legible por el LLM.
     */
    static String buildContextBlock(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "No se encontraron documentos relevantes para esta pregunta.";
        }
        StringBuilder block = new StringBuilder();
        for (int index = 0; index < hits.size(); index++) {
            SearchHit hit = hits.get(index);
            block.append("[Fuente ").append(index + 1).append("] ").append(hit.sourceKey())
                    .append(" (chunk ").append(hit.chunkIndex() == null ? "?" : hit.chunkIndex())
                    .append(")\n")
                    .append(hit.text() == null ? "(sin texto disponible)" : hit.text())
                    .append("\n\n");
        }
        return block.toString().trim();
    }

    /**
     * Extrae y deduplica las fuentes únicas basadas en la combinación {@code source_key#chunk_index},
     * preservando el orden relativo de mayor a menor relevancia determinado por Pinecone.
     *
     * @param hits Lista de resultados de búsqueda.
     * @return Lista de objetos {@link Source} únicos para adjuntar en la respuesta final de la API.
     */
    static List<Source> buildSources(List<SearchHit> hits) {
        LinkedHashMap<String, Source> unique = new LinkedHashMap<>();
        if (hits != null) {
            for (SearchHit hit : hits) {
                if (hit.sourceKey() == null) {
                    continue;
                }
                unique.putIfAbsent(hit.sourceKey() + "#" + hit.chunkIndex(),
                        new Source(hit.sourceKey(), hit.chunkIndex(), hit.score()));
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Representa una fuente de información citada o disponible para la respuesta.
     *
     * @param sourceKey Ruta o nombre del documento PDF en S3.
     * @param chunkIndex Índice numérico del chunk de origen.
     * @param score Puntuación de similitud semántica obtenida en la búsqueda.
     */
    public record Source(String sourceKey, String chunkIndex, double score) {
    }
}