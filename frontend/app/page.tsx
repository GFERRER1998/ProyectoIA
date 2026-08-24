"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";
import { listDocuments, listSessions } from "@/lib/api/client";

const navigation = [{ label: "Conversaciones", path: "/chat" }, { label: "Documentos", path: "/documents" }, { label: "Historial", path: "/history" }];

/** Renderiza la pantalla base que se usará como shell autenticado. */
export default function HomePage() {
  const { status, user, logout } = useAuth();
  const [sessionCount, setSessionCount] = useState<number | null>(null);
  const [documentCount, setDocumentCount] = useState<number | null>(null);
  const [apiError, setApiError] = useState("");

  /** Carga los recursos principales una vez que Cognito confirma la sesión. */
  useEffect(() => {
    if (status !== "authenticated") return;
    Promise.all([listSessions(), listDocuments()])
      .then(([sessions, documents]) => {
        setSessionCount(sessions.length);
        setDocumentCount(documents.length);
      })
      .catch((error: unknown) => setApiError(error instanceof Error ? error.message : "No se pudo cargar el dashboard."));
  }, [status]);

  /** Renderiza el estado de carga mientras Amplify resuelve la sesión local. */
  if (status === "loading") return <main className="auth-page"><section className="auth-card">Cargando sesión...</section></main>;
  if (status !== "authenticated") return <main className="auth-page"><section className="auth-card"><h1>Inicia sesión para entrar.</h1><Link className="primary-button auth-submit" href="/login">Ir al login</Link></section></main>;

  return (
    <main className="app-shell">
      <aside className="sidebar" aria-label="Navegación principal">
        <div className="brand-mark" aria-label="Proyecto IA">
          <span className="brand-dot" />
          <span>Proyecto IA</span>
        </div>
        <nav>
          {navigation.map((item, index) => (
            <Link className={index === 0 ? "nav-item active" : "nav-item"} href={item.path} key={item.path}>
              <span className="nav-icon" aria-hidden="true">{index === 0 ? "◌" : index === 1 ? "□" : "≡"}</span>
              {item.label}
            </Link>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="status-dot" />
          <span>Backend conectado</span>
        </div>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">Área de trabajo</p>
            <h1>Conversaciones</h1>
          </div>
          <button className="profile-button" onClick={() => void logout()} type="button" aria-label="Cerrar sesión">
            <span className="avatar">IA</span>
            <span className="profile-label">{user?.username ?? "Invitado"}</span>
          </button>
        </header>

        <div className="content-grid">
          <section className="welcome-card">
            <div className="card-kicker">Asistente documental</div>
            <h2>Pregunta. Explora. Comprende.</h2>
            <p>
              Sube tus documentos y conversa con la información importante, con respuestas
              respaldadas por fuentes.
            </p>
            <Link className="primary-button" href="/chat">Nueva conversación <span>→</span></Link>
          </section>

          <section className="empty-state" aria-label="Estado de conversaciones">
            <div className="empty-orbit"><span>✦</span></div>
            <p className="eyebrow">Primer paso</p>
            <h2>Aún no hay conversaciones</h2>
            <p>Comienza una conversación o carga un documento para empezar a consultar.</p>
            <div className="dashboard-stats"><span>{sessionCount ?? "—"} conversaciones</span><span>{documentCount ?? "—"} documentos</span></div>
            {apiError && <p className="form-error">{apiError}</p>}
          </section>
        </div>
      </section>
    </main>
  );
}
