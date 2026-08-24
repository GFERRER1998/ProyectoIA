"use client";

import { useState, type FormEvent, type KeyboardEvent } from "react";
import { ask } from "@/lib/api/client";
import type { Source } from "@/lib/api/types";

type Message = {
  role: "user" | "assistant";
  content: string;
  sources?: Source[];
};

/** Renderiza el chat RAG y conserva el session_id de la conversación actual. */
export function ChatPanel() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [question, setQuestion] = useState("");
  const [sessionId, setSessionId] = useState<string>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  /** Envía una pregunta y agrega la respuesta con sus fuentes al transcript. */
  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = question.trim();
    if (!value || loading) return;
    setQuestion("");
    setError("");
    setMessages((current) => [...current, { role: "user", content: value }]);
    setLoading(true);
    try {
      const response = await ask(value, sessionId);
      setSessionId(response.session_id);
      setMessages((current) => [...current, { role: "assistant", content: response.answer, sources: response.sources }]);
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : "No se pudo completar la consulta.");
    } finally {
      setLoading(false);
    }
  }

  /** Envía con Enter y permite saltos de línea con Shift+Enter. */
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  }

  /** Reinicia el transcript y crea una conversación independiente. */
  function startNewConversation() {
    setMessages([]);
    setSessionId(undefined);
    setQuestion("");
    setError("");
  }

  return (
    <section className="chat-panel">
      <header className="chat-header">
        <div><p className="eyebrow">Asistente RAG</p><h2>Conversación</h2></div>
        <button className="secondary-button" onClick={startNewConversation} type="button">Nueva conversación</button>
      </header>
      <div className="chat-transcript" aria-live="polite">
        {messages.length === 0 && <div className="chat-empty"><span>✦</span><h3>¿Qué quieres explorar?</h3><p>Pregunta sobre tus documentos y recibirás una respuesta con fuentes.</p></div>}
        {messages.map((message, index) => (
          <article className={`chat-message ${message.role}`} key={`${message.role}-${index}`}>
            <div className="message-role">{message.role === "user" ? "Tú" : "Asistente"}</div>
            <p>{message.content}</p>
            {message.sources && message.sources.length > 0 && <div className="source-list"><span>Fuentes</span>{message.sources.map((source) => <small key={`${source.sourceKey}-${source.chunkIndex}`}>{source.sourceKey} · chunk {source.chunkIndex}</small>)}</div>}
          </article>
        ))}
        {loading && <div className="chat-message assistant"><div className="message-role">Asistente</div><p className="typing">Consultando documentos...</p></div>}
      </div>
      {error && <p className="form-error chat-error">{error}</p>}
      <form className="chat-composer" onSubmit={handleSubmit}>
        <textarea aria-label="Escribe tu pregunta" onChange={(event) => setQuestion(event.target.value)} onKeyDown={handleKeyDown} placeholder="Escribe una pregunta sobre tus documentos..." value={question} />
        <div className="composer-footer"><span>Enter para enviar · Shift+Enter salto de línea</span><button className="primary-button chat-send" disabled={loading || !question.trim()} type="submit">Enviar ↑</button></div>
      </form>
    </section>
  );
}
