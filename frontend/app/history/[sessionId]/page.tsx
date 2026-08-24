"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ProtectedRoute } from "@/components/auth/ProtectedRoute";
import { HistoryDetail } from "@/components/history/HistoryDetail";

/** Renderiza el detalle privado de una conversación seleccionada. */
export default function HistoryDetailPage() {
  const params = useParams<{ sessionId: string }>();
  return <ProtectedRoute><main className="feature-page"><Link className="back-link" href="/history">← Historial</Link><HistoryDetail sessionId={decodeURIComponent(params.sessionId)} /></main></ProtectedRoute>;
}
