import "server-only";

import { AsyncLocalStorage } from "node:async_hooks";

import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";
import * as client from "openid-client";

import type { Session } from "@/lib/session";
import { isRole } from "@/lib/session";
import { serverConfig } from "./config";

/**
 * Signing in with the identity project's Keycloak (realm "reviews"), as an
 * OpenID Connect confidential client: authorization code flow with PKCE. The
 * access token is the bearer token the storage service accepts.
 */

/** The audience Keycloak adds to access tokens for the storage service. */
export const STORAGE_AUDIENCE = "storage";

/** The user's tokens, kept in the encrypted session cookie. */
export interface Tokens {
  access: string;
  refresh?: string;
  /** When the access token expires, in seconds since the epoch. */
  expiresAt: number;
}

/** What the callback needs to finish signing in, kept in a short-lived encrypted cookie. */
export interface LoginState {
  state: string;
  nonce: string;
  verifier: string;
  /** The application page to return to. */
  next: string;
}

const globalForOidc = globalThis as typeof globalThis & {
  oidcConfiguration?: Promise<client.Configuration>;
  oidcKeys?: JWTVerifyGetKey;
};

/**
 * The client a call to Keycloak is for (finishLogin, refresh), sent as
 * X-Forwarded-For, so Keycloak records their address, not this server's. It
 * trusts the header from this server only (KC_PROXY_TRUSTED_ADDRESSES).
 */
const forwarding = new AsyncLocalStorage<string | undefined>();

const forwardingFetch: client.CustomFetch = (url, options) => {
  const address = forwarding.getStore();
  const init = options as RequestInit & { headers: Record<string, string> };
  return fetch(url, address ? { ...init, headers: { ...init.headers, "X-Forwarded-For": address } } : init);
};

/** Keycloak's endpoints, discovered once (and again after a failure). */
export function configuration(): Promise<client.Configuration> {
  globalForOidc.oidcConfiguration ??= discover().catch((err: unknown) => {
    globalForOidc.oidcConfiguration = undefined;
    throw err;
  });
  return globalForOidc.oidcConfiguration;
}

async function discover(): Promise<client.Configuration> {
  const config = await discoverEndpoints();
  config[client.customFetch] = forwardingFetch;
  return config;
}

async function discoverEndpoints(): Promise<client.Configuration> {
  const { issuer, keycloakInternalUrl, clientId, clientSecret } = serverConfig();
  const url = new URL(issuer);
  const authentication = client.ClientSecretPost(clientSecret);
  if (!keycloakInternalUrl) {
    // Development runs Keycloak over plain HTTP on localhost.
    const options = url.protocol === "http:" ? { execute: [client.allowInsecureRequests] } : undefined;
    return client.discovery(url, clientId, undefined, authentication, options);
  }
  const config = new client.Configuration(await internalMetadata(issuer, keycloakInternalUrl), clientId, undefined,
    authentication);
  if (new URL(keycloakInternalUrl).protocol === "http:") {
    client.allowInsecureRequests(config);
  }
  return config;
}

/**
 * Keycloak's metadata, read at its internal address, with every endpoint this
 * server calls (tokens, keys, sign-out) moved there too. The issuer, and the
 * sign-in page browsers are sent to, stay at the public address.
 */
async function internalMetadata(issuer: string, internalUrl: string): Promise<client.ServerMetadata> {
  const publicOrigin = new URL(issuer).origin;
  const internal = (endpoint: string) =>
    endpoint.startsWith(publicOrigin) ? new URL(endpoint.slice(publicOrigin.length), internalUrl).href : endpoint;
  const response = await fetch(internal(`${issuer}/.well-known/openid-configuration`), { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`Keycloak's metadata: ${response.status}`);
  }
  const metadata = (await response.json()) as client.ServerMetadata;
  if (metadata.issuer !== issuer) {
    throw new Error(`Keycloak's issuer is ${metadata.issuer}, not ${issuer}`);
  }
  return Object.fromEntries(
    Object.entries(metadata).map(([key, value]) =>
      (key.endsWith("_endpoint") || key === "jwks_uri") && key !== "authorization_endpoint" && typeof value === "string"
        ? [key, internal(value)]
        : [key, value],
    ),
  ) as client.ServerMetadata;
}

/** Where Keycloak sends the browser back to: `/api/auth/callback`, as registered for the client. */
export function callbackUrl(origin: URL): URL {
  return new URL("/api/auth/callback", origin);
}

/**
 * The URL of Keycloak's sign-in page (or, to register, its registration page,
 * which then signs the new person in), and the state the callback checks.
 */
export async function startLogin(origin: URL, next: string, register: boolean): Promise<{ url: URL; state: LoginState }> {
  const config = await configuration();
  const state: LoginState = {
    state: client.randomState(),
    nonce: client.randomNonce(),
    verifier: client.randomPKCECodeVerifier(),
    next,
  };
  const url = client.buildAuthorizationUrl(config, {
    redirect_uri: callbackUrl(origin).href,
    scope: "openid",
    code_challenge: await client.calculatePKCECodeChallenge(state.verifier),
    code_challenge_method: "S256",
    state: state.state,
    nonce: state.nonce,
  });
  if (register) {
    // Keycloak's registration endpoint takes the same parameters as its authorization endpoint.
    url.pathname = url.pathname.replace(/\/auth$/, "/registrations");
  }
  return { url, state };
}

/** Exchanges the callback's code, checking its state, nonce and PKCE verifier. */
export async function finishLogin(currentUrl: URL, state: LoginState, clientAddress?: string): Promise<Tokens> {
  const config = await configuration();
  const tokens = await forwarding.run(clientAddress, () =>
    client.authorizationCodeGrant(config, currentUrl, {
      expectedState: state.state,
      expectedNonce: state.nonce,
      pkceCodeVerifier: state.verifier,
      idTokenExpected: true,
    }),
  );
  return tokensOf(tokens);
}

/** New tokens from the refresh token, before the access token expires. */
export async function refresh(tokens: Tokens, clientAddress?: string): Promise<Tokens> {
  if (!tokens.refresh) {
    throw new Error("No refresh token");
  }
  const config = await configuration();
  const renewed = await forwarding.run(clientAddress, () => client.refreshTokenGrant(config, tokens.refresh!));
  // Keycloak sends a new refresh token each time; keep the old one if it did not.
  return { ...tokensOf(renewed), refresh: renewed.refresh_token ?? tokens.refresh };
}

function tokensOf(response: client.TokenEndpointResponse): Tokens {
  return {
    access: response.access_token,
    refresh: response.refresh_token,
    expiresAt: Math.floor(Date.now() / 1000) + (response.expires_in ?? 60),
  };
}

/**
 * The person an access token names: signed by Keycloak, unexpired, for the
 * storage service, and with a verified email and a role.
 */
export async function identify(accessToken: string): Promise<Session | null> {
  const config = await configuration();
  const metadata = config.serverMetadata();
  globalForOidc.oidcKeys ??= createRemoteJWKSet(new URL(metadata.jwks_uri!));
  try {
    const { payload } = await jwtVerify(accessToken, globalForOidc.oidcKeys, {
      issuer: metadata.issuer,
      audience: STORAGE_AUDIENCE,
    });
    const { email, email_verified: verified, name, role } = payload;
    if (typeof email !== "string" || verified !== true || !isRole(role)) {
      return null;
    }
    return { email, name: typeof name === "string" && name ? name : email, role };
  } catch {
    return null;
  }
}

/**
 * Ends the Keycloak session from the server (back-channel logout with the
 * refresh token), so signing out needs no trip to Keycloak's pages.
 */
export async function endSession(tokens: Tokens, clientAddress?: string): Promise<void> {
  const config = await configuration();
  const endpoint = config.serverMetadata().end_session_endpoint;
  if (!endpoint || !tokens.refresh) {
    return;
  }
  const { clientId, clientSecret } = serverConfig();
  await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      ...(clientAddress ? { "X-Forwarded-For": clientAddress } : {}),
    },
    body: new URLSearchParams({ client_id: clientId, client_secret: clientSecret, refresh_token: tokens.refresh }),
    cache: "no-store",
  }).catch(() => undefined);
}
