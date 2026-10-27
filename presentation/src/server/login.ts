import "server-only";

import { NextResponse, type NextRequest } from "next/server";

import { serverConfig } from "./config";
import { LOGIN_COOKIE, publicOrigin, safeNext, setCookie } from "./http";
import { startLogin } from "./oidc";
import { seal } from "./sealed";

/** Long enough to type a password, or to find the verification email. */
const LOGIN_MAX_AGE = 30 * 60;

/** Only the callback reads the sign-in in progress. */
export const LOGIN_COOKIE_PATH = "/api/auth/callback";

/** Sends the browser to Keycloak's sign-in or registration page. */
export async function redirectToKeycloak(request: NextRequest, register: boolean): Promise<NextResponse> {
  const origin = publicOrigin(request);
  const { url, state } = await startLogin(origin, safeNext(request.nextUrl.searchParams.get("next")), register);
  const response = NextResponse.redirect(url, 303);
  setCookie(response, request, LOGIN_COOKIE, await seal({ ...state }, serverConfig().sessionSecret, LOGIN_MAX_AGE), {
    maxAge: LOGIN_MAX_AGE,
    path: LOGIN_COOKIE_PATH,
  });
  return response;
}
