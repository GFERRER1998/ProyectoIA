"use client";

import { Amplify } from "aws-amplify";
import { cognitoConfig } from "./config";

let configured = false;

/** Configura Amplify una sola vez para evitar reconfiguraciones durante el desarrollo. */
export function configureAmplify() {
  if (configured || !cognitoConfig.userPoolId || !cognitoConfig.userPoolClientId) return;

  Amplify.configure(
    {
      Auth: {
        Cognito: {
          userPoolId: cognitoConfig.userPoolId,
          userPoolClientId: cognitoConfig.userPoolClientId,
          loginWith: { email: true },
          signUpVerificationMethod: "code",
        },
      },
    },
    { ssr: true },
  );
  configured = true;
}
