"use client";

import { fetchAuthSession } from "aws-amplify/auth";
import { configureAmplify } from "@/lib/auth/amplify";
import type {
  ChatResponse,
  Document,
  SessionDetail,
  SessionSummary,
  UploadPreparation,
  ViewUrl,
} from "./types";

type ApiOptions = RequestInit & { retryAuth?: boolean };

/** Devuelve una variable pública obligatoria y evita llamadas a URLs indefinidas. */
function requiredUrl(name: string, value: string | undefined) {
  if (!value) throw new Error(`Falta configurar ${name} en el entorno del frontend.`);
  return value.replace(/\/$/, "");
}

/** Une una URL de Function URL con una ruta sin duplicar separadores. */
function endpoint(base: string | undefined, name: string, path = "") {
  return `${requiredUrl(name, base)}${path}`;
}

/** Obtiene el ID Token actual de Cognito para autenticar una llamada privada. */
async function idToken(forceRefresh = false) {
  configureAmplify();
  const session = await fetchAuthSession({ forceRefresh });
  const token = session.tokens?.idToken?.toString();
  if (!token) throw new Error("La sesión Cognito no contiene un ID Token válido.");
  return token;
}

/** Ejecuta una petición autenticada y renueva el token una sola vez si recibe 401. */
async function authorizedFetch<T>(url: string, options: ApiOptions = {}): Promise<T> {
  const { retryAuth = true, headers, ...request } = options;
  const suppliedAuthorization = new Headers(headers).get("Authorization");
  const token = suppliedAuthorization?.replace(/^Bearer\s+/i, "") ?? await idToken(false);
  const response = await fetch(url, {
    ...request,
    headers: { "Content-Type": "application/json", ...headers, Authorization: `Bearer ${token}` },
  });

  if (response.status === 401 && retryAuth) {
    return authorizedFetch<T>(url, { ...options, retryAuth: false, headers: { ...headers, Authorization: `Bearer ${await idToken(true)}` } });
  }
  if (!response.ok) throw new Error(await errorMessage(response));
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

/** Extrae un mensaje HTTP controlado sin mostrar respuestas internas al usuario. */
async function errorMessage(response: Response) {
  try {
    const body = await response.json() as { error?: string };
    return body.error ?? "La API no pudo completar la operación.";
  } catch {
    return `La API respondió con HTTP ${response.status}.`;
  }
}

/** Envía una pregunta al endpoint RAG y conserva el identificador de sesión. */
export function ask(question: string, sessionId?: string) {
  return authorizedFetch<ChatResponse>(endpoint(process.env.NEXT_PUBLIC_QUERY_API_URL, "NEXT_PUBLIC_QUERY_API_URL"), {
    method: "POST",
    body: JSON.stringify({ question, session_id: sessionId }),
  });
}

/** Obtiene la lista de documentos del usuario autenticado. */
export async function listDocuments() {
  const response = await authorizedFetch<{ documents: Document[] }>(endpoint(process.env.NEXT_PUBLIC_DOCUMENTS_API_URL, "NEXT_PUBLIC_DOCUMENTS_API_URL", "/documents"));
  return response.documents;
}

/** Obtiene una URL temporal para visualizar un documento PDF propio. */
export function getDocumentViewUrl(documentId: string) {
  return authorizedFetch<ViewUrl>(endpoint(process.env.NEXT_PUBLIC_DOCUMENTS_API_URL, "NEXT_PUBLIC_DOCUMENTS_API_URL", `/documents/${encodeURIComponent(documentId)}/view-url`));
}

/** Elimina un documento propio y sus recursos asociados en el backend. */
export function deleteDocument(documentId: string) {
  return authorizedFetch<void>(endpoint(process.env.NEXT_PUBLIC_DOCUMENTS_API_URL, "NEXT_PUBLIC_DOCUMENTS_API_URL", `/documents/${encodeURIComponent(documentId)}`), { method: "DELETE" });
}

/** Obtiene el listado resumido de conversaciones del usuario autenticado. */
export async function listSessions() {
  const response = await authorizedFetch<{ sessions: SessionSummary[] }>(endpoint(process.env.NEXT_PUBLIC_SESSIONS_API_URL, "NEXT_PUBLIC_SESSIONS_API_URL", "/sessions"));
  return response.sessions;
}

/** Obtiene el detalle completo de una conversación propia. */
export function getSession(sessionId: string) {
  return authorizedFetch<SessionDetail>(endpoint(process.env.NEXT_PUBLIC_SESSIONS_API_URL, "NEXT_PUBLIC_SESSIONS_API_URL", `/sessions/${encodeURIComponent(sessionId)}`));
}

/** Elimina una conversación propia y devuelve cuando el backend confirma 204. */
export function deleteSession(sessionId: string) {
  return authorizedFetch<void>(endpoint(process.env.NEXT_PUBLIC_SESSIONS_API_URL, "NEXT_PUBLIC_SESSIONS_API_URL", `/sessions/${encodeURIComponent(sessionId)}`), { method: "DELETE" });
}

/** Solicita una URL PUT y sube el PDF directamente a S3 sin pasar por la Lambda. */
export async function uploadDocument(file: File) {
  const preparation = await authorizedFetch<UploadPreparation>(endpoint(process.env.NEXT_PUBLIC_UPLOAD_API_URL, "NEXT_PUBLIC_UPLOAD_API_URL"), {
    method: "POST",
    body: JSON.stringify({ fileName: file.name, contentType: file.type, size: file.size }),
  });
  const uploadResponse = await fetch(preparation.uploadUrl, { method: "PUT", headers: { "Content-Type": "application/pdf" }, body: file });
  if (!uploadResponse.ok) throw new Error("No se pudo subir el PDF a almacenamiento.");
  return preparation;
}
