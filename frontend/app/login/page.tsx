"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { login } from "@/lib/auth/actions";
import { hasCognitoConfig } from "@/lib/auth/config";

/** Renderiza el formulario de inicio de sesión con Cognito. */
export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);

  /** Envía las credenciales y redirige al área privada cuando Cognito autentica. */
  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setMessage("");
    const result = await login(email, password);
    setLoading(false);
    if (!result.ok) {
      setMessage(result.message ?? "No se pudo iniciar sesión.");
      return;
    }
    router.push("/");
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand-mark auth-brand"><span className="brand-dot" />Proyecto IA</div>
        <p className="eyebrow">Acceso privado</p>
        <h1>Vuelve a tus documentos.</h1>
        <p className="auth-copy">Inicia sesión para consultar tus conversaciones y fuentes.</p>
        {!hasCognitoConfig() && <p className="form-error">Configura las variables de Cognito en `.env.local`.</p>}
        <form onSubmit={handleSubmit}>
          <label>Email<input required type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label>
          <label>Contraseña<input required minLength={8} type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
          {message && <p className="form-error">{message}</p>}
          <button className="primary-button auth-submit" disabled={loading || !hasCognitoConfig()} type="submit">{loading ? "Ingresando..." : "Iniciar sesión"}</button>
        </form>
        <p className="auth-switch">¿No tienes cuenta? <Link href="/register">Regístrate</Link></p>
      </section>
    </main>
  );
}
