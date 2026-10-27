import { NextResponse, type NextRequest } from "next/server";

import type { Session } from "@/lib/session";
import { clientAddressOf } from "@/server/client-address";
import { serverConfig } from "@/server/config";
import { clearCookie, LOGIN_COOKIE, publicOrigin } from "@/server/http";
import { LOGIN_COOKIE_PATH } from "@/server/login";
import { callbackUrl, finishLogin, identify, type LoginState } from "@/server/oidc";
import { unseal } from "@/server/sealed";
import { currentSession, sealSession, setSessionCookie, updateSessionCookie } from "@/server/session";
import { callStorage } from "@/server/storage";

/**
 * Where Keycloak sends people back after they sign in or register: exchanges
 * the code for tokens, registers the person with the storage service the
 * first time, keeps the tokens in the session cookie and returns to the page
 * that asked them to sign in.
 */
export async function GET(request: NextRequest): Promise<Response> {
  const origin = publicOrigin(request);
  const params = request.nextUrl.searchParams;
  const state = await unseal<LoginState>(request.cookies.get(LOGIN_COOKIE)?.value, serverConfig().sessionSecret);

  if (params.has("error")) {
    // Keycloak reports a refusal (or a cancelled sign-in) instead of a code.
    return leave(request, origin, "/login?error=refused");
  }
  if (!state || state.state !== params.get("state")) {
    // No sign-in in progress here: Back or Forward onto Keycloak's page, which
    // signed the user straight in again, or a sign-in that took too long.
    const current = await currentSession(request);
    const response = leave(request, origin, current.user ? "/" : "/login?error=expired");
    updateSessionCookie(response, request, current.cookie);
    return response;
  }

  // The URL Keycloak redirected to, as the browser saw it (behind any proxy).
  const currentUrl = callbackUrl(origin);
  currentUrl.search = request.nextUrl.search;
  let tokens;
  try {
    tokens = await finishLogin(currentUrl, state, clientAddressOf(request.headers));
  } catch {
    return leave(request, origin, "/login?error=failed");
  }
  const user = await identify(tokens.access);
  if (!user) {
    // Created without a role (by an administrator) or without a verified email.
    return leave(request, origin, "/login?error=account");
  }
  if (!(await registerPerson(request, tokens.access, user))) {
    return leave(request, origin, "/login?error=storage");
  }

  const response = leave(request, origin, state.next);
  setSessionCookie(response, request, await sealSession(tokens));
  return response;
}

function leave(request: NextRequest, origin: URL, path: string): NextResponse {
  const response = NextResponse.redirect(new URL(path, origin), 303);
  clearCookie(response, LOGIN_COOKIE, LOGIN_COOKIE_PATH);
  return response;
}

/**
 * The storage service knows people once they register with it (POST /people,
 * named by the email in their token). Doing it on every sign-in, when they are
 * not registered yet, also heals a registration that failed halfway.
 */
async function registerPerson(request: NextRequest, token: string, user: Session): Promise<boolean> {
  const ld = { Accept: "application/ld+json", "Content-Type": "application/ld+json" };
  const me = await callStorage(request, { method: "GET", path: "people/me", headers: new Headers(ld), token });
  if (me.ok) {
    return true;
  }
  if (me.status !== 403) {
    return false;
  }
  const registered = await callStorage(request, {
    method: "POST",
    path: "people",
    headers: new Headers(ld),
    body: JSON.stringify({ type: "Person", name: user.name }),
    token,
  });
  // 409: registered meanwhile (another tab).
  return registered.ok || registered.status === 409;
}
