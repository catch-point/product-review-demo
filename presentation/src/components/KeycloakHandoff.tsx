"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useEffect } from "react";

import { safePath } from "@/lib/safe-path";
import { useSessionQuery } from "@/store/auth";
import { homeOf } from "./RequireSession";
import { ErrorMessage, FormCard, Spinner } from "./ui";

/** What `/api/auth/callback` reports in `?error=`. */
const ERRORS: Record<string, string> = {
  refused: "Signing in was cancelled or refused.",
  expired: "That sign-in page expired. Please sign in again.",
  failed: "Signing in did not complete. Please try again.",
  account: "This account cannot use Product Reviews: it has no role, or its email address is not verified.",
  storage: "You are signed in, but the product service could not register you. Please try again.",
};

const TITLES = { login: "Sign in", register: "Register" } as const;

/**
 * /login and /register: Keycloak shows the sign-in and registration pages
 * (identity/themes/reviews), so these hand over to it. They replace
 * their own history entry, so Back from Keycloak returns to the page before.
 * After an error they stay, with a way to try again, rather than loop.
 */
export function KeycloakHandoff({ kind }: { kind: "login" | "register" }) {
  const params = useSearchParams();
  const router = useRouter();
  const { data: session, isLoading } = useSessionQuery();
  const error = params.get("error");
  const next = params.get("next");
  const target = `/api/auth/${kind}${next ? `?next=${encodeURIComponent(next)}` : ""}`;

  useEffect(() => {
    if (isLoading) {
      return;
    }
    if (session) {
      router.replace(safePath(next) ?? homeOf(session.role));
    } else if (!error) {
      window.location.replace(target);
    }
  }, [isLoading, session, error, next, target, router]);

  if (!error || session) {
    return <Spinner label={kind === "login" ? "Taking you to sign in…" : "Taking you to register…"} />;
  }
  return (
    <FormCard title={TITLES[kind]}>
      <div className="space-y-6">
        <ErrorMessage>{ERRORS[error] ?? "Signing in did not complete. Please try again."}</ErrorMessage>
        <a
          href={target}
          className="flex w-full justify-center rounded-md bg-blue-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white hover:bg-blue-500"
        >
          {kind === "login" ? "Sign in" : "Register"}
        </a>
      </div>
    </FormCard>
  );
}
