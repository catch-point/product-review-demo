import { createHash } from "node:crypto";

import { exportJWK, generateKeyPair, SignJWT } from "jose";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { endSession, finishLogin, identify, refresh, startLogin } from "./oidc";

const ISSUER = "http://localhost:8180/realms/reviews";
const APP = new URL("http://localhost:3000/");
const OIDC = `${ISSUER}/protocol/openid-connect`;

/** Keycloak's metadata names its public address, wherever it is read. */
const METADATA = {
  issuer: ISSUER,
  authorization_endpoint: `${OIDC}/auth`,
  token_endpoint: `${OIDC}/token`,
  jwks_uri: `${OIDC}/certs`,
  end_session_endpoint: `${OIDC}/logout`,
};

const realmKeys = await generateKeyPair("RS256");
const otherKeys = await generateKeyPair("RS256");
const JWKS = { keys: [{ ...(await exportJWK(realmKeys.publicKey)), kid: "realm", alg: "RS256", use: "sig" }] };

interface Call {
  url: string;
  method: string;
  form: URLSearchParams;
  forwardedFor: string | null;
}

/** A fake Keycloak, behind fetch: what it was asked, and how it answers. */
let calls: Call[];
let discovery: () => Response;
let token: (form: URLSearchParams) => Promise<Response>;
let logout: () => Promise<Response>;

const callsTo = (path: string) => calls.filter((c) => new URL(c.url).pathname.endsWith(path));

beforeEach(() => {
  // oidc.ts keeps Keycloak's configuration and keys for the life of the server.
  const cached = globalThis as { oidcConfiguration?: unknown; oidcKeys?: unknown };
  delete cached.oidcConfiguration;
  delete cached.oidcKeys;
  calls = [];
  discovery = () => Response.json(METADATA);
  token = async () => Response.json({ error: "invalid_grant" }, { status: 400 });
  logout = async () => new Response(null, { status: 204 });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(input, init);
      const form = new URLSearchParams(request.method === "POST" ? await request.text() : "");
      calls.push({ url: request.url, method: request.method, form, forwardedFor: request.headers.get("x-forwarded-for") });
      const path = new URL(request.url).pathname;
      if (path.endsWith("/.well-known/openid-configuration")) return discovery();
      if (path.endsWith("/certs")) return Response.json(JWKS);
      if (path.endsWith("/token")) return token(form);
      if (path.endsWith("/logout")) return logout();
      return new Response(null, { status: 404 });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

interface Signing {
  issuer?: string;
  audience?: string | string[];
  expires?: number;
  key?: CryptoKey;
}

const now = () => Math.floor(Date.now() / 1000);

/** An access token as Keycloak issues them for the storage service. */
function accessToken(claims: Record<string, unknown> = {}, signing: Signing = {}): Promise<string> {
  const { issuer = ISSUER, audience = ["storage", "account"], expires = now() + 300, key = realmKeys.privateKey } = signing;
  return new SignJWT({ email: "rob@example.com", email_verified: true, name: "Rob", role: "reviewer", ...claims })
    .setProtectedHeader({ alg: "RS256", kid: "realm" })
    .setIssuer(issuer)
    .setAudience(audience)
    .setSubject("rob")
    .setIssuedAt()
    .setExpirationTime(expires)
    .sign(key);
}

function idToken(nonce: string): Promise<string> {
  return new SignJWT({ nonce })
    .setProtectedHeader({ alg: "RS256", kid: "realm" })
    .setIssuer(ISSUER)
    .setAudience("presentation")
    .setSubject("rob")
    .setIssuedAt()
    .setExpirationTime("5m")
    .sign(realmKeys.privateKey);
}

describe("startLogin", () => {
  it("sends the browser to Keycloak's sign-in page, with PKCE, state and nonce", async () => {
    const { url, state } = await startLogin(APP, "/products/1", false);
    expect(`${url.origin}${url.pathname}`).toBe(`${OIDC}/auth`);
    const params = url.searchParams;
    expect(params.get("client_id")).toBe("presentation");
    expect(params.get("response_type")).toBe("code");
    expect(params.get("scope")).toBe("openid");
    expect(params.get("redirect_uri")).toBe("http://localhost:3000/api/auth/callback");
    expect(params.get("state")).toBe(state.state);
    expect(params.get("nonce")).toBe(state.nonce);
    expect(params.get("code_challenge_method")).toBe("S256");
    expect(params.get("code_challenge")).toBe(createHash("sha256").update(state.verifier).digest("base64url"));
    expect(state.next).toBe("/products/1");
  });

  it("sends people registering to the registration page, with the same parameters", async () => {
    const { url, state } = await startLogin(APP, "/", true);
    expect(`${url.origin}${url.pathname}`).toBe(`${OIDC}/registrations`);
    expect(url.searchParams.get("state")).toBe(state.state);
  });

  it("starts each sign-in afresh", async () => {
    const first = await startLogin(APP, "/", false);
    const second = await startLogin(APP, "/", false);
    expect(second.state.state).not.toBe(first.state.state);
    expect(second.state.nonce).not.toBe(first.state.nonce);
    expect(second.state.verifier).not.toBe(first.state.verifier);
  });

  it("discovers Keycloak once, and again after a failure", async () => {
    discovery = () => new Response("Starting", { status: 503 });
    await expect(startLogin(APP, "/", false)).rejects.toThrow();
    discovery = () => Response.json(METADATA);
    await startLogin(APP, "/", false);
    await startLogin(APP, "/", false);
    expect(callsTo("/.well-known/openid-configuration")).toHaveLength(2);
  });
});

describe("finishLogin", () => {
  async function callback() {
    const { state } = await startLogin(APP, "/", false);
    const url = new URL("/api/auth/callback", APP);
    url.search = new URLSearchParams({ code: "the-code", state: state.state }).toString();
    return { state, url };
  }

  it("exchanges the code, with the PKCE verifier and the client's secret", async () => {
    const { state, url } = await callback();
    const access = await accessToken();
    token = async () =>
      Response.json({
        access_token: access,
        refresh_token: "refresh-1",
        id_token: await idToken(state.nonce),
        token_type: "Bearer",
        expires_in: 300,
      });

    const tokens = await finishLogin(url, state);

    expect(tokens).toMatchObject({ access, refresh: "refresh-1" });
    expect(tokens.expiresAt).toBeGreaterThanOrEqual(now() + 299);
    expect(tokens.expiresAt).toBeLessThanOrEqual(now() + 300);
    const [exchange] = callsTo("/token");
    expect(Object.fromEntries(exchange.form)).toMatchObject({
      grant_type: "authorization_code",
      code: "the-code",
      code_verifier: state.verifier,
      redirect_uri: "http://localhost:3000/api/auth/callback",
      client_id: "presentation",
      client_secret: "test-client-secret",
    });
  });

  it("refuses a callback for another sign-in, without exchanging its code", async () => {
    const { state, url } = await callback();
    url.searchParams.set("state", "someone-elses");
    await expect(finishLogin(url, state)).rejects.toThrow();
    expect(callsTo("/token")).toHaveLength(0);
  });

  it("refuses an ID token for another sign-in", async () => {
    const { state, url } = await callback();
    token = async () =>
      Response.json({
        access_token: await accessToken(),
        id_token: await idToken("another-nonce"),
        token_type: "Bearer",
        expires_in: 300,
      });
    await expect(finishLogin(url, state)).rejects.toThrow();
  });
});

describe("refresh", () => {
  const renewal = (refreshToken?: string) => async () =>
    Response.json({
      access_token: await accessToken(),
      token_type: "Bearer",
      expires_in: 300,
      ...(refreshToken ? { refresh_token: refreshToken } : {}),
    });

  it("renews the access token with the refresh token", async () => {
    token = renewal("refresh-2");
    const renewed = await refresh({ access: "old", refresh: "refresh-1", expiresAt: 0 });
    expect(renewed.access).not.toBe("old");
    expect(renewed.refresh).toBe("refresh-2");
    expect(renewed.expiresAt).toBeGreaterThan(now());
    expect(Object.fromEntries(callsTo("/token")[0].form)).toMatchObject({
      grant_type: "refresh_token",
      refresh_token: "refresh-1",
    });
  });

  it("tells Keycloak whose tokens it renews, when it knows", async () => {
    token = renewal("refresh-2");
    await refresh({ access: "old", refresh: "refresh-1", expiresAt: 0 }, "203.0.113.7");
    await refresh({ access: "old", refresh: "refresh-2", expiresAt: 0 });
    expect(callsTo("/token").map((c) => c.forwardedFor)).toEqual(["203.0.113.7", null]);
  });

  it("keeps the refresh token when Keycloak sends no new one", async () => {
    token = renewal();
    expect((await refresh({ access: "old", refresh: "refresh-1", expiresAt: 0 })).refresh).toBe("refresh-1");
  });

  it("fails when Keycloak no longer honours the refresh token, or there is none", async () => {
    await expect(refresh({ access: "old", refresh: "ended", expiresAt: 0 })).rejects.toThrow();
    await expect(refresh({ access: "old", expiresAt: 0 })).rejects.toThrow("No refresh token");
  });
});

describe("identify", () => {
  it("names the person in a valid access token", async () => {
    expect(await identify(await accessToken())).toEqual({ email: "rob@example.com", name: "Rob", role: "reviewer" });
    expect(await identify(await accessToken({ role: "owner", name: "" }))).toEqual({
      email: "rob@example.com",
      name: "rob@example.com",
      role: "owner",
    });
  });

  it("reads Keycloak's keys once", async () => {
    await identify(await accessToken());
    await identify(await accessToken());
    expect(callsTo("/certs")).toHaveLength(1);
  });

  it("refuses tokens Keycloak did not sign, for another audience or issuer, or expired", async () => {
    expect(await identify(await accessToken({}, { key: otherKeys.privateKey }))).toBeNull();
    expect(await identify(await accessToken({}, { audience: "account" }))).toBeNull();
    expect(await identify(await accessToken({}, { issuer: "http://localhost:8180/realms/master" }))).toBeNull();
    expect(await identify(await accessToken({}, { expires: now() - 60 }))).toBeNull();
    expect(await identify("not-a-token")).toBeNull();
  });

  it("refuses people without a verified email or a role", async () => {
    expect(await identify(await accessToken({ email_verified: false }))).toBeNull();
    expect(await identify(await accessToken({ email: undefined }))).toBeNull();
    expect(await identify(await accessToken({ role: undefined }))).toBeNull();
    expect(await identify(await accessToken({ role: "admin" }))).toBeNull();
  });
});

describe("endSession", () => {
  it("signs out at Keycloak with the refresh token", async () => {
    await endSession({ access: "a", refresh: "refresh-1", expiresAt: 0 });
    const [call] = callsTo("/logout");
    expect(call.method).toBe("POST");
    expect(Object.fromEntries(call.form)).toEqual({
      client_id: "presentation",
      client_secret: "test-client-secret",
      refresh_token: "refresh-1",
    });
    expect(call.forwardedFor).toBeNull();

    await endSession({ access: "a", refresh: "refresh-1", expiresAt: 0 }, "203.0.113.7");
    expect(callsTo("/logout")[1].forwardedFor).toBe("203.0.113.7");
  });

  it("does without a refresh token, and when Keycloak cannot be reached", async () => {
    await endSession({ access: "a", expiresAt: 0 });
    expect(callsTo("/logout")).toHaveLength(0);
    logout = () => Promise.reject(new TypeError("fetch failed"));
    await expect(endSession({ access: "a", refresh: "refresh-1", expiresAt: 0 })).resolves.toBeUndefined();
  });
});

describe("with KEYCLOAK_INTERNAL_URL", () => {
  beforeEach(() => {
    vi.stubEnv("KEYCLOAK_INTERNAL_URL", "http://keycloak:8080");
  });

  it("calls Keycloak there, but sends browsers to its public address", async () => {
    const { url } = await startLogin(APP, "/", false);
    expect(calls[0].url).toBe("http://keycloak:8080/realms/reviews/.well-known/openid-configuration");
    expect(`${url.origin}${url.pathname}`).toBe(`${OIDC}/auth`);

    token = async () => Response.json({ access_token: await accessToken(), token_type: "Bearer", expires_in: 300 });
    await refresh({ access: "old", refresh: "refresh-1", expiresAt: 0 });
    await identify(await accessToken());
    await endSession({ access: "a", refresh: "refresh-1", expiresAt: 0 });
    expect(calls.slice(1).map((c) => c.url)).toEqual([
      "http://keycloak:8080/realms/reviews/protocol/openid-connect/token",
      "http://keycloak:8080/realms/reviews/protocol/openid-connect/certs",
      "http://keycloak:8080/realms/reviews/protocol/openid-connect/logout",
    ]);
  });

  it("still checks the tokens' issuer, the public address", async () => {
    expect(await identify(await accessToken())).not.toBeNull();
    expect(await identify(await accessToken({}, { issuer: "http://keycloak:8080/realms/reviews" }))).toBeNull();
  });

  it("refuses metadata for another issuer", async () => {
    discovery = () => Response.json({ ...METADATA, issuer: "http://keycloak:8080/realms/reviews" });
    await expect(startLogin(APP, "/", false)).rejects.toThrow("Keycloak's issuer");
  });
});
