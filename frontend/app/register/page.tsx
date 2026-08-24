"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { register } from "@/lib/auth/actions";
import { hasCognitoConfig } from "@/lib/auth/config";

/** Renderiza el formulario de registro de nuevos usuarios Cognito. */
export default function RegisterPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(false);

  /** Valida la contraseña y crea la cuenta antes de ir a la confirmación de email. */
  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (password !== confirmation) {
      setMessage("Las contraseñas no coinciden.");
      return;
    }
    setLoading(true);
    setMessage("");
    const result = await register(email, password);
    setLoading(false);
    if (!result.ok) {
      setMessage(result.message ?? "No se pudo crear la cuenta.");
      return;
    }
    router.push(`/confirm?email=${encodeURIComponent(email)}`);
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand-mark auth-brand"><span className="brand-dot" />Proyecto IA</div>
        <p className="eyebrow">Nuevo acceso</p>
        <h1>Crea tu espacio documental.</h1>
        <p className="auth-copy">Regístrate para empezar a conversar con tus PDFs.</p>
        {!hasCognitoConfig() && <p className="form-error">Configura las variables de Cognito en `.env.local`.</p>}
        <form onSubmit={handleSubmit}>
          <label>Email<input required type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label>
          <label>Contraseña<input required minLength={8} type="password" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
          <label>Confirmar contraseña<input required minLength={8} type="password" value={confirmation} onChange={(event) => setConfirmation(event.target.value)} /></label>
          {message && <p className="form-error">{message}</p>}
          <button className="primary-button auth-submit" disabled={loading || !hasCognitoConfig()} type="submit">{loading ? "Creando..." : "Crear cuenta"}</button>
        </form>
        <p className="auth-switch">¿Ya tienes cuenta? <Link href="/login">Inicia sesión</Link></p>
      </section>
    </main>
  );
}
