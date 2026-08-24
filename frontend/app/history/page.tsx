"use client";

import Link from "next/link";
import { ProtectedRoute } from "@/components/auth/ProtectedRoute";
import { HistoryPanel } from "@/components/history/HistoryPanel";

/** Renderiza la ruta privada con el listado de conversaciones. */
export default function HistoryPage() {
  return <ProtectedRoute><main className="feature-page"><Link className="back-link" href="/">← Dashboard</Link><HistoryPanel /></main></ProtectedRoute>;
}
