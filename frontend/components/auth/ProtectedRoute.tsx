"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";

/** Protege una ruta cliente y redirige al login cuando no hay sesión Cognito. */
export function ProtectedRoute({ children }: Readonly<{ children: ReactNode }>) {
  const router = useRouter();
  const { status } = useAuth();

  useEffect(() => {
    if (status === "unauthenticated" || status === "unconfigured") router.replace("/login");
  }, [router, status]);

  if (status !== "authenticated") return <main className="auth-page"><section className="auth-card">Validando sesión...</section></main>;
  return children;
}
