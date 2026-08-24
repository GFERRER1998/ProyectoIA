"use client";

import Link from "next/link";
import { ProtectedRoute } from "@/components/auth/ProtectedRoute";
import { DocumentPanel } from "@/components/documents/DocumentPanel";

/** Renderiza la ruta autenticada de gestión de PDFs. */
export default function DocumentsPage() {
  return <ProtectedRoute><main className="feature-page"><Link className="back-link" href="/">← Dashboard</Link><DocumentPanel /></main></ProtectedRoute>;
}
