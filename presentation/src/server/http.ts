import "server-only";

import { NextResponse, type NextRequest } from "next/server";

import { forwardingHeadersChecked } from "./client-address";
import { publicUrl } from "./config";

export const SESSION_COOKIE = "session";

/** The sign-in in progress (src/server/oidc.ts LoginState), read only by the callback. */
export const LOGIN_COOKIE = "login";

/** An RFC 9457 problem, as the storage service reports its errors. */
export function problem(status: number, title: string, detail?: string): NextResponse {
  return NextResponse.json(
    { type: "about:blank", title, status, ...(detail ? { detail } : {}) },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

/**
 * The origin the browser sees: PUBLIC_URL when it is set (as it is in Docker,
 * and must be in production: instrumentation.ts), or else the request's,
 * behind any reverse proxy. X-Forwarded-Host and -Proto are believed only
 * from a trusted proxy: client-address.cjs drops them from anyone else, and
 * without it they are not read at all.
 */
export function publicOrigin(request: NextRequest): URL {
  const configured = publicUrl();
  if (configured) {
    return configured;
  }
  const url = new URL(request.url);
  const forwarded = forwardingHeadersChecked();
  const proto = forwarded ? request.headers.get("x-forwarded-proto")?.split(",")[0].trim() : undefined;
  const host =
    (forwarded ? request.headers.get("x-forwarded-host")?.split(",")[0].trim() : undefined) ??
    request.headers.get("host");
  if (proto) {
    url.protocol = `${proto}:`;
  }
  if (host) {
    url.host = host;
  }
  url.pathname = "/";
  url.search = "";
  return url;
}

/**
 * Refuses state-changing requests sent from other sites. The session cookie
 * is SameSite=Lax, which already keeps it off them; this also covers browsers
 * that ignore SameSite.
 */
export function crossSite(request: NextRequest): boolean {
  const origin = request.headers.get("origin");
  return origin !== null && origin !== publicOrigin(request).origin;
}

/** Stands in for this application's origin while a `next` path is resolved. */
const NEXT_BASE = "http://application.invalid";

/**
 * A path of this application to return to after signing in: `/products/1?x=y`, never another site.
 * It is resolved as the redirect will resolve it, since the URL parser drops tabs and newlines and
 * reads `\` as `/`: `/\t/evil.example` is `//evil.example`, another site.
 */
export function safeNext(next: string | null | undefined): string {
  if (!next?.startsWith("/")) {
    return "/";
  }
  let url: URL;
  try {
    url = new URL(next, NEXT_BASE);
  } catch {
    return "/";
  }
  return url.origin === NEXT_BASE ? `${url.pathname}${url.search}${url.hash}` : "/";
}

export function setCookie(
  response: NextResponse,
  request: NextRequest,
  name: string,
  value: string,
  { maxAge, path = "/" }: { maxAge: number; path?: string },
): void {
  response.cookies.set(name, value, {
    httpOnly: true,
    sameSite: "lax",
    secure: publicOrigin(request).protocol === "https:",
    path,
    maxAge,
  });
  uncacheable(response);
}

export function clearCookie(response: NextResponse, name: string, path = "/"): void {
  response.cookies.set(name, "", { httpOnly: true, sameSite: "lax", path, maxAge: 0 });
  uncacheable(response);
}

/**
 * A response that sets a cookie is this browser's alone, even when its body
 * (a storage resource relayed with `Cache-Control: no-cache`) is the same for
 * everyone: no cache may keep it, or a shared one could hand the cookie, and
 * with it the session, to other people.
 */
function uncacheable(response: NextResponse): void {
  response.headers.set("Cache-Control", "private, no-store");
}
