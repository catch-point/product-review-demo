import type { Metadata } from "next";
import { Suspense } from "react";

import { KeycloakHandoff } from "@/components/KeycloakHandoff";
import { Spinner } from "@/components/ui";

export const metadata: Metadata = { title: "Sign in" };

export default function LoginPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <KeycloakHandoff kind="login" />
    </Suspense>
  );
}
