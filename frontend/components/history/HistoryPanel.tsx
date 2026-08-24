"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { deleteSession, listSessions } from "@/lib/api/client";
import type { SessionSummary } from "@/lib/api/types";

/** Renderiza el listado de conversaciones persistidas del usuario autenticado. */
export function HistoryPanel() {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    void listSessions().then(setSessions).catch((caught: unknown) => {
      setError(caught instanceof Error ? caught.message : "No se pudo cargar el historial.");
    }).finally(() => setLoading(false));
  }, []);

  /** Elimina una conversación y actualiza la lista sin recargar la página. */
  async function removeSession(sessionId: string) {
    if (!window.confirm("¿Eliminar esta conversación?")) return;
    try {
      await deleteSession(sessionId);
      setSessions((current) => current.filter((session) => session.sessionId !== sessionId));
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : "No se pudo eliminar la conversación.");
    }
  }

  return (
    <section className="history-panel">
      <header className="feature-header"><div><p className="eyebrow">Memoria del asistente</p><h1>Historial</h1><p>Tus conversaciones anteriores, siempre disponibles.</p></div><Link className="primary-button" href="/chat">Nueva conversación <span>→</span></Link></header>
      {error && <p className="form-error history-error">{error}</p>}
      {loading ? <p className="muted-copy">Cargando conversaciones...</p> : sessions.length === 0 ? <div className="history-empty"><span>◌</span><h2>Aún no hay conversaciones</h2><p>Cuando converses con tus documentos, aparecerán aquí.</p></div> : <div className="history-list">{sessions.map((session) => <article className="history-row" key={session.sessionId}><Link href={`/history/${encodeURIComponent(session.sessionId)}`}><strong>{session.title || "Conversación sin título"}</strong><small>{session.messageCount} mensajes · {formatDate(session.updatedAt)}</small><p>{session.lastMessagePreview || "Sin vista previa"}</p></Link><button className="text-button danger-button" onClick={() => void removeSession(session.sessionId)} type="button">Eliminar</button></article>)}</div>}
    </section>
  );
}

/** Formatea la fecha de actividad para el idioma del navegador. */
function formatDate(value: string) {
  if (!value) return "sin fecha";
  return new Intl.DateTimeFormat("es", { dateStyle: "medium" }).format(new Date(value));
}
