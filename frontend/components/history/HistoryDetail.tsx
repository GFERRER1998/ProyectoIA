"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { getSession } from "@/lib/api/client";
import type { SessionDetail as SessionDetailData } from "@/lib/api/types";

/** Carga y presenta todos los mensajes de una conversación persistida. */
export function HistoryDetail({ sessionId }: Readonly<{ sessionId: string }>) {
  const [session, setSession] = useState<SessionDetailData | null>(null);
  const [error, setError] = useState("");

  /** Consulta el detalle de la conversación al abrir la ruta. */
  useEffect(() => {
    getSession(sessionId).then(setSession).catch((caught: unknown) => setError(caught instanceof Error ? caught.message : "No se pudo cargar la conversación."));
  }, [sessionId]);

  if (error) return <section className="auth-card"><p className="form-error">{error}</p><Link className="back-link" href="/history">Volver al historial</Link></section>;
  if (!session) return <section className="auth-card">Cargando conversación...</section>;

  return <section className="history-detail"><header className="detail-header"><div><p className="eyebrow">Conversación guardada</p><h1>{session.title || "Conversación sin título"}</h1></div><Link className="secondary-button" href="/history">← Historial</Link></header><div className="detail-messages">{session.messages.map((message, index) => <article className={`chat-message ${message.role}`} key={`${message.role}-${index}`}><div className="message-role">{message.role === "user" ? "Tú" : "Asistente"}</div><p>{message.content}</p></article>)}</div></section>;
}
