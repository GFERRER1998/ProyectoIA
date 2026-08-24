"use client";

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { fetchAuthSession, getCurrentUser, signOut } from "aws-amplify/auth";
import { configureAmplify } from "@/lib/auth/amplify";
import { hasCognitoConfig } from "@/lib/auth/config";
import type { AuthStatus, AuthUser } from "@/lib/auth/types";

type AuthContextValue = {
  status: AuthStatus;
  user: AuthUser | null;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

/** Proporciona el estado de sesión Cognito a todas las rutas del frontend. */
export function AuthProvider({ children }: Readonly<{ children: ReactNode }>) {
  const [status, setStatus] = useState<AuthStatus>(hasCognitoConfig() ? "loading" : "unconfigured");
  const [user, setUser] = useState<AuthUser | null>(null);

  /** Consulta la sesión vigente y sincroniza el usuario autenticado con la UI. */
  async function refresh() {
    if (!hasCognitoConfig()) {
      setStatus("unconfigured");
      return;
    }
    try {
      configureAmplify();
      const current = await getCurrentUser();
      await fetchAuthSession();
      setUser({ userId: current.userId, username: current.username });
      setStatus("authenticated");
    } catch {
      setUser(null);
      setStatus("unauthenticated");
    }
  }

  /** Cierra la sesión local y remota de Cognito antes de limpiar el estado visual. */
  async function logout() {
    await signOut();
    setUser(null);
    setStatus("unauthenticated");
  }

  useEffect(() => {
    // Cognito es una fuente externa: sincronizamos su sesión al montar el provider.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void refresh();
  }, []);

  return <AuthContext.Provider value={{ status, user, refresh, logout }}>{children}</AuthContext.Provider>;
}

/** Obtiene el contexto de autenticación y falla rápido fuera de AuthProvider. */
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth debe utilizarse dentro de AuthProvider");
  return context;
}
