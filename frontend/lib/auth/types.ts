export type AuthStatus = "loading" | "authenticated" | "unauthenticated" | "unconfigured";

export type AuthUser = {
  userId: string;
  username: string;
};

export type AuthResult = {
  ok: boolean;
  message?: string;
  requiresConfirmation?: boolean;
};
