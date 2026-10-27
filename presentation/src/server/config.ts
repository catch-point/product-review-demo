import "server-only";

export interface ServerConfig {
  /** The storage service, which these route handlers proxy as /api/storage. */
  storageUrl: string;
  /** The Keycloak realm people sign in with (its issuer URL, as browsers reach it). */
  issuer: string;
  /**
   * Where this server reaches Keycloak, when not at the issuer's address (in
   * Docker, http://keycloak:8080). Browsers still go to the issuer's.
   */
  keycloakInternalUrl?: string;
  /** This application's client in that realm. */
  clientId: string;
  clientSecret: string;
  /** Encrypts the session cookie, which holds the user's tokens. */
  sessionSecret: string;
}

/**
 * The origin browsers reach this application at (PUBLIC_URL, such as
 * `http://localhost:3000`), when it is configured. It names the application in
 * Keycloak redirects and the storage service's IRIs, instead of the request's
 * Host or X-Forwarded-Host, which any client can set: a response built from
 * them could be kept by a shared cache and served to everyone.
 */
export function publicUrl(): URL | undefined {
  const configured = process.env.PUBLIC_URL?.trim();
  if (!configured) {
    return undefined;
  }
  let url: URL;
  try {
    url = new URL(configured);
  } catch {
    throw new Error(`PUBLIC_URL is not a URL: ${configured}`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error(`PUBLIC_URL must be an http or https URL: ${configured}`);
  }
  return new URL(url.origin);
}

/** Read on first use, so `next build` never needs production secrets. */
export function serverConfig(): ServerConfig {
  const clientSecret = process.env.KEYCLOAK_CLIENT_SECRET ?? "";
  const sessionSecret = process.env.SESSION_SECRET ?? "";
  // In production the secrets must be set (scripts/secrets.sh generates them
  // for the Compose stack), unless the deployment says it is a development
  // one with ALLOW_DEV_SECRETS=true.
  if (
    process.env.NODE_ENV === "production" &&
    process.env.ALLOW_DEV_SECRETS !== "true" &&
    clientSecret.length === 0
  ) {
    throw new Error("Set KEYCLOAK_CLIENT_SECRET in production");
  }
  if (sessionSecret.length < 32) {
    throw new Error("SESSION_SECRET must be at least 32 characters");
  }
  return {
    storageUrl: (process.env.STORAGE_URL ?? "http://localhost:8080").replace(/\/+$/, ""),
    issuer: (process.env.KEYCLOAK_ISSUER ?? "http://localhost:8180/realms/reviews").replace(/\/+$/, ""),
    keycloakInternalUrl: process.env.KEYCLOAK_INTERNAL_URL || undefined,
    clientId: process.env.KEYCLOAK_CLIENT_ID ?? "presentation",
    clientSecret,
    sessionSecret,
  };
}
