export const cognitoConfig = {
  userPoolId: process.env.NEXT_PUBLIC_COGNITO_USER_POOL_ID ?? "",
  userPoolClientId: process.env.NEXT_PUBLIC_COGNITO_CLIENT_ID ?? "",
};

/** Devuelve si la configuración mínima de Cognito está disponible en el navegador. */
export function hasCognitoConfig() {
  return Boolean(cognitoConfig.userPoolId && cognitoConfig.userPoolClientId);
}
