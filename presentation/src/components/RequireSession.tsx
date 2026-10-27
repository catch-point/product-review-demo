"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useSyncExternalStore, type ReactNode } from "react";

import type { Role, Session } from "@/lib/session";
import { useSessionQuery } from "@/store/auth";
import { selectSignedOut } from "@/store/authSlice";
import { useAppSelector } from "@/store/store";
import { ErrorMessage, Spinner } from "./ui";

/** Where each role starts. */
export function homeOf(role: Role): string {
  return role === "owner" ? "/my-products" : "/";
}

const subscribeToNothing = () => () => {};

/**
 * False on the server and while hydrating, true after. The server never knows
 * the session, so it renders the spinner; the session may arrive (the nav bar
 * asks for it first) before this hydrates, and then the first render here must
 * still be the server's spinner, or React discards the server's HTML.
 */
function useHydrated(): boolean {
  return useSyncExternalStore(subscribeToNothing, () => true, () => false);
}

/**
 * Renders its children for a signed-in user (of the given role). Anyone else
 * is sent on with history.replaceState, so Back never returns to a page they
 * could not see: to sign in (and then back here), or to their own role's home.
 * Signing out leaves for the sign-in page alone.
 *
 * This only decides what to show. /api/storage enforces the same roles.
 */
export function RequireSession({ role, children }: { role?: Role; children: (session: Session) => ReactNode }) {
  const { data: session, isLoading, isError } = useSessionQuery();
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams().toString();
  const signedOut = useAppSelector(selectSignedOut);
  const hydrated = useHydrated();

  const redirect = isLoading || isError ? null : !session ? "login" : role && session.role !== role ? "home" : null;

  useEffect(() => {
    if (redirect === "login") {
      const next = pathname + (search ? `?${search}` : "");
      router.replace(signedOut ? "/login" : `/login?next=${encodeURIComponent(next)}`);
    } else if (redirect === "home" && session) {
      router.replace(homeOf(session.role));
    }
  }, [redirect, session, signedOut, pathname, search, router]);

  if (!hydrated) {
    return <Spinner />;
  }
  if (isError) {
    return (
      <div className="mx-auto max-w-6xl px-4 py-10">
        <ErrorMessage>Could not check whether you are signed in. Reload the page to try again.</ErrorMessage>
      </div>
    );
  }
  if (!session || redirect) {
    return <Spinner />;
  }
  return <>{children(session)}</>;
}
