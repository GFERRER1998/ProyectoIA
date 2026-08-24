"use client";

import Link from "next/link";
import { ProtectedRoute } from "@/components/auth/ProtectedRoute";
import { ChatPanel } from "@/components/chat/ChatPanel";

/** Renderiza la ruta autenticada de conversación RAG. */
export default function ChatPage() {
  return <ProtectedRoute><main className="feature-page"><Link className="back-link" href="/">← Dashboard</Link><ChatPanel /></main></ProtectedRoute>;
}
