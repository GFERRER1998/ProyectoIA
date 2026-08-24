"use client";

import { confirmSignUp, signIn, signUp } from "aws-amplify/auth";
import { configureAmplify } from "./amplify";
import type { AuthResult } from "./types";

const genericError = "No se pudo completar la operación. Revisa los datos e inténtalo de nuevo.";

/** Registra un usuario en Cognito y devuelve si requiere confirmar su correo. */
export async function register(email: string, password: string): Promise<AuthResult> {
  try {
    configureAmplify();
    const result = await signUp({ username: email, password, options: { userAttributes: { email } } });
    return { ok: true, requiresConfirmation: result.nextStep.signUpStep !== "DONE" };
  } catch {
    return { ok: false, message: genericError };
  }
}

/** Confirma mediante código el email utilizado durante el registro. */
export async function confirmRegistration(email: string, code: string): Promise<AuthResult> {
  try {
    configureAmplify();
    await confirmSignUp({ username: email, confirmationCode: code });
    return { ok: true };
  } catch {
    return { ok: false, message: "El código no es válido o ya expiró." };
  }
}

/** Autentica un usuario y reporta si Cognito requiere un paso adicional. */
export async function login(email: string, password: string): Promise<AuthResult> {
  try {
    configureAmplify();
    const result = await signIn({ username: email, password });
    if (!result.isSignedIn) return { ok: false, message: "La cuenta requiere un paso adicional de autenticación." };
    return { ok: true };
  } catch {
    return { ok: false, message: "Email o contraseña incorrectos." };
  }
}
