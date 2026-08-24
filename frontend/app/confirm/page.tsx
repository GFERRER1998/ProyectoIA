"use client";

import Link from "next/link";
import { Suspense, useState, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import { confirmRegistration } from "@/lib/auth/actions";

/** Renderiza la confirmación del código enviado por Cognito al email registrado. */
export default function ConfirmPage() {
  return <Suspense fallback={<main className="auth-page"><section className="auth-card">Cargando confirmación...</section></main>}><ConfirmForm /></Suspense>;
}

/** Gestiona el formulario que depende de los parámetros de búsqueda del navegador. */
function ConfirmForm() {
  const searchParams = useSearchParams();
  const [code, setCode] = useState("");
  const [message, setMessage] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const email = searchParams.get("email") ?? "";

  /** Confirma el código y deja disponible el enlace de inicio de sesión. */
  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const result = await confirmRegistration(email, code);
    if (!result.ok) {
      setMessage(result.message ?? "No se pudo confirmar el email.");
      return;
    }
    setConfirmed(true);
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="brand-mark auth-brand"><span className="brand-dot" />Proyecto IA</div>
        <p className="eyebrow">Verificación</p>
        <h1>Confirma tu email.</h1>
        <p className="auth-copy">Introduce el código que enviamos a {email || "tu correo"}.</p>
        {confirmed ? <p className="success-message">Email confirmado. Ya puedes iniciar sesión.</p> : (
          <form onSubmit={handleSubmit}>
            <label>Código de confirmación<input required inputMode="numeric" value={code} onChange={(event) => setCode(event.target.value)} /></label>
            {message && <p className="form-error">{message}</p>}
            <button className="primary-button auth-submit" type="submit">Confirmar email</button>
          </form>
        )}
        <p className="auth-switch"><Link href="/login">Ir a iniciar sesión</Link></p>
      </section>
    </main>
  );
}
