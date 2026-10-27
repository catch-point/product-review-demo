import "server-only";

import type { NextRequest, NextResponse } from "next/server";

import type { Session } from "@/lib/session";
import { clientAddressOf } from "./client-address";
import { serverConfig } from "./config";
import { clearCookie, SESSION_COOKIE, setCookie } from "./http";
import { identify, refresh, type Tokens } from "./oidc";
import { seal, unseal } from "./sealed";

/** As long as the realm's longest session (ssoSessionMaxLifespan in reviews-realm.json). */
const SESSION_MAX_AGE = 10 * 3600;

/** Renew the access token this long before it expires, so a request never carries an expired one. */
const RENEW_BEFORE = 30;

export interface CurrentSession {
  user: Session | null;
  /** The access token: the bearer token for the storage service. */
  accessToken?: string;
  /**
   * How the session cookie must change: a new sealed value after the tokens
   * were renewed, null to clear it (the session ended), or undefined to leave it.
   */
  cookie?: string | null;
}

/** The signed-in user, renewing their tokens first when they are about to expire. */
export async function currentSession(request: NextRequest): Promise<CurrentSession> {
  const sealed = request.cookies.get(SESSION_COOKIE)?.value;
  const secret = serverConfig().sessionSecret;
  let tokens = await unseal<Tokens>(sealed, secret);
  if (!tokens) {
    return { user: null, cookie: sealed ? null : undefined };
  }
  let cookie: string | undefined;
  if (tokens.expiresAt - RENEW_BEFORE <= Date.now() / 1000) {
    try {
      tokens = await refresh(tokens, clientAddressOf(request.headers));
      cookie = await sealSession(tokens);
    } catch {
      // The Keycloak session ended (signed out elsewhere, idle or too old).
      return { user: null, cookie: null };
    }
  }
  const user = await identify(tokens.access);
  return user ? { user, accessToken: tokens.access, cookie } : { user: null, cookie: null };
}

export function sealSession(tokens: Tokens): Promise<string> {
  return seal({ ...tokens }, serverConfig().sessionSecret, SESSION_MAX_AGE);
}

export async function tokensOf(request: NextRequest): Promise<Tokens | null> {
  return unseal<Tokens>(request.cookies.get(SESSION_COOKIE)?.value, serverConfig().sessionSecret);
}

/** Applies CurrentSession.cookie to a response. */
export function updateSessionCookie(response: NextResponse, request: NextRequest, cookie: string | null | undefined): void {
  if (cookie === null) {
    clearCookie(response, SESSION_COOKIE);
  } else if (cookie !== undefined) {
    setSessionCookie(response, request, cookie);
  }
}

export function setSessionCookie(response: NextResponse, request: NextRequest, cookie: string): void {
  setCookie(response, request, SESSION_COOKIE, cookie, { maxAge: SESSION_MAX_AGE });
}
