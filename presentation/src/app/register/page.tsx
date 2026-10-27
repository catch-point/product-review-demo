import type { Metadata } from "next";
import { Suspense } from "react";

import { KeycloakHandoff } from "@/components/KeycloakHandoff";
import { Spinner } from "@/components/ui";

export const metadata: Metadata = { title: "Register" };

export default function RegisterPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <KeycloakHandoff kind="register" />
    </Suspense>
  );
}
