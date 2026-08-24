package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.Map;

/** Expone el listado, detalle y eliminación segura del historial conversacional. */
public class SessionHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final SessionStore sessionStore;
    private final CognitoJwtVerifier jwtVerifier;

    /** Construye el handler de producción usando DynamoDB y Cognito. */
    public SessionHandler() {
        this(SessionStore.fromEnvironment(), CognitoJwtVerifier.fromEnvironment());
    }

    /** Constructor inyectable para probar autenticación y respuestas sin AWS real. */
    SessionHandler(SessionStore sessionStore, CognitoJwtVerifier jwtVerifier) {
        this.sessionStore = sessionStore;
        this.jwtVerifier = jwtVerifier;
    }

    /** Autentica la petición y enruta la operación de historial solicitada. */
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String userId = jwtVerifier.verify(authorizationHeader(event));
            String path = event == null || event.getRawPath() == null ? "/sessions" : event.getRawPath();
            String method = method(event);
            if ("GET".equalsIgnoreCase(method) && "/sessions".equals(path)) {
                return list(userId);
            }
            if ("GET".equalsIgnoreCase(method)) {
                return detail(userId, sessionId(path));
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                return delete(userId, sessionId(path));
            }
            return error(405, "Metodo no permitido");
        } catch (UnauthorizedException e) {
            return error(401, "No autenticado. Se requiere un ID Token de Cognito valido.");
        } catch (SessionNotFoundException e) {
            return error(404, "Conversacion no encontrada");
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (Exception e) {
            return error(500, "No se pudo consultar el historial.");
        }
    }

    /** Serializa los resúmenes de conversación del usuario autenticado. */
    private APIGatewayV2HTTPResponse list(String userId) {
        JsonArray sessions = new JsonArray();
        sessionStore.listSessions(userId).forEach(summary -> {
            JsonObject item = new JsonObject();
            item.addProperty("sessionId", summary.sessionId());
            item.addProperty("title", summary.title());
            item.addProperty("lastMessagePreview", summary.lastMessagePreview());
            item.addProperty("messageCount", summary.messageCount());
            item.addProperty("createdAt", summary.createdAt());
            item.addProperty("updatedAt", summary.updatedAt());
            sessions.add(item);
        });
        JsonObject body = new JsonObject();
        body.add("sessions", sessions);
        body.add("nextToken", JsonNull.INSTANCE);
        return jsonResponse(200, body.toString());
    }

    /** Serializa una conversación completa después de validar propiedad. */
    private APIGatewayV2HTTPResponse detail(String userId, String clientSessionId) {
        SessionStore.SessionDetail detail = sessionStore.getSession(userId, clientSessionId);
        if (detail == null) throw new SessionNotFoundException();
        JsonObject body = new JsonObject();
        body.addProperty("sessionId", detail.sessionId());
        body.addProperty("title", detail.title());
        body.addProperty("createdAt", detail.createdAt());
        body.addProperty("updatedAt", detail.updatedAt());
        JsonArray messages = new JsonArray();
        detail.messages().forEach(message -> {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            item.addProperty("timestamp", message.timestamp());
            messages.add(item);
        });
        body.add("messages", messages);
        return jsonResponse(200, body.toString());
    }

    /** Elimina una conversación solo si pertenece al usuario autenticado. */
    private APIGatewayV2HTTPResponse delete(String userId, String clientSessionId) {
        if (!sessionStore.deleteSession(userId, clientSessionId)) throw new SessionNotFoundException();
        return APIGatewayV2HTTPResponse.builder().withStatusCode(204).build();
    }

    /** Extrae el UUID de sesión desde una ruta /sessions/{sessionId}. */
    private static String sessionId(String path) {
        String[] parts = path.split("/");
        if (parts.length != 3 || parts[2].isBlank()) throw new IllegalArgumentException("Session ID invalido");
        return parts[2];
    }

    /** Obtiene el método HTTP de payload v2 con un valor seguro por defecto. */
    private static String method(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getRequestContext() == null || event.getRequestContext().getHttp() == null) {
            return "GET";
        }
        return event.getRequestContext().getHttp().getMethod();
    }

    /** Busca Authorization ignorando diferencias de capitalización del proxy. */
    private static String authorizationHeader(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getHeaders() == null) return null;
        return event.getHeaders().entrySet().stream()
                .filter(entry -> entry.getKey() != null && "authorization".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** Construye una respuesta JSON de error sin filtrar información interna. */
    private static APIGatewayV2HTTPResponse error(int status, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        return jsonResponse(status, body.toString());
    }

    /** Construye una respuesta JSON con el content type esperado por el frontend. */
    private static APIGatewayV2HTTPResponse jsonResponse(int status, String body) {
        return APIGatewayV2HTTPResponse.builder().withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json")).withBody(body).build();
    }

    /** Excepción interna para diferenciar una sesión inexistente de una entrada inválida. */
    private static final class SessionNotFoundException extends RuntimeException { }
}
