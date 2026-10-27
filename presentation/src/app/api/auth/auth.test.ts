import { NextRequest, type NextResponse } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Session } from "@/lib/session";
import { endSession, finishLogin, identify, startLogin, type LoginState } from "@/server/oidc";
import { seal, unseal } from "@/server/sealed";
import { sealSession } from "@/server/session";
import { GET as callback } from "./callback/route";
import { GET as login } from "./login/route";
import { POST as logout } from "./logout/route";
import { GET as register } from "./register/route";
import { GET as session } from "./session/route";

// Route handlers run outside a Next.js request here, where connection() would throw.
vi.mock("next/server", async (importOriginal) => ({
  ...(await importOriginal<typeof import("next/server")>()),
  connection: vi.fn(async () => undefined),
}));

vi.mock("@/server/oidc", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/server/oidc")>()),
  startLogin: vi.fn(),
  finishLogin: vi.fn(),
  identify: vi.fn(),
  refresh: vi.fn(),
  endSession: vi.fn(),
}));

const APP = "http://localhost:3000";
const SECRET = process.env.SESSION_SECRET!;
const ROB: Session = { email: "rob@example.com", name: "Rob", role: "reviewer" };
const STATE: LoginState = { state: "state-1", nonce: "nonce-1", verifier: "verifier-1", next: "/products/1" };
const TOKENS = { access: "access-1", refresh: "refresh-1", expiresAt: Math.floor(Date.now() / 1000) + 300 };

let storage: ReturnType<typeof vi.fn<(url: string, init: RequestInit) => Promise<Response>>>;

beforeEach(() => {
  storage = vi.fn(async () => Response.json({}));
  vi.stubGlobal("fetch", storage);
  vi.mocked(endSession).mockResolvedValue(undefined);
  vi.mocked(startLogin).mockImplementation(async (_origin, next) => ({
    url: new URL("http://localhost:8180/realms/reviews/protocol/openid-connect/auth?state=state-1"),
    state: { ...STATE, next },
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

function request(path: string, { cookie, ...init }: { cookie?: string; method?: string; headers?: Record<string, string> } = {}) {
  return new NextRequest(new URL(path, APP), {
    ...init,
    headers: { ...init.headers, ...(cookie ? { cookie } : {}) },
  });
}

/** The callback's response, as Keycloak sends the browser back with the sign-in in progress. */
async function returning(query: string, state: LoginState | null = STATE): Promise<NextResponse> {
  const cookie = state ? `login=${await seal({ ...state }, SECRET, 60)}` : undefined;
  // The handler answers with NextResponse redirects, whose cookies the tests read.
  return (await callback(request(`/api/auth/callback?${query}`, { cookie }))) as NextResponse;
}

describe("/api/auth/login and /api/auth/register", () => {
  it("send the browser to Keycloak with the sign-in in progress sealed in a cookie only the callback reads", async () => {
    const response = (await login(request("/api/auth/login?next=/products/1"))) as NextResponse;

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toMatch(/^http:\/\/localhost:8180\/realms\/reviews\//);
    expect(startLogin).toHaveBeenCalledWith(new URL(`${APP}/`), "/products/1", false);
    const cookie = response.cookies.get("login");
    expect(cookie?.path).toBe("/api/auth/callback");
    expect(cookie?.httpOnly).toBe(true);
    expect(await unseal<LoginState>(cookie?.value, SECRET)).toMatchObject({ state: "state-1", next: "/products/1" });
  });

  it("return only to this application", async () => {
    for (const next of ["https://evil.example/", "//evil.example", "/%09/evil.example", "/%0A/evil.example"]) {
      await login(request(`/api/auth/login?next=${next}`));
      expect(startLogin).toHaveBeenLastCalledWith(expect.any(URL), "/", false);
    }
  });

  it("register on Keycloak's registration page", async () => {
    await register(request("/api/auth/register?next=/"));
    expect(startLogin).toHaveBeenCalledWith(expect.any(URL), "/", true);
  });
});

describe("/api/auth/callback", () => {
  it("signs in a registered person and returns to the page that asked", async () => {
    vi.mocked(finishLogin).mockResolvedValue(TOKENS);
    vi.mocked(identify).mockResolvedValue(ROB);

    const response = await returning("code=code-1&state=state-1");

    expect(response.status).toBe(303);
    expect(response.headers.get("location")).toBe(`${APP}/products/1`);
    expect(await unseal(response.cookies.get("session")?.value, SECRET)).toMatchObject({ access: "access-1" });
    expect(response.cookies.get("login")?.value).toBe("");
    expect(storage).toHaveBeenCalledOnce();
    expect(storage.mock.calls[0][0]).toMatch(/\/people\/me$/);
  });

  it("registers a new person with the storage service on their first sign-in", async () => {
    vi.mocked(finishLogin).mockResolvedValue(TOKENS);
    vi.mocked(identify).mockResolvedValue(ROB);
    storage.mockResolvedValueOnce(new Response(null, { status: 403 })).mockResolvedValueOnce(new Response(null, { status: 201 }));

    const response = await returning("code=code-1&state=state-1");

    expect(response.headers.get("location")).toBe(`${APP}/products/1`);
    const [url, init] = storage.mock.calls[1];
    expect(url).toMatch(/\/people$/);
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ type: "Person", name: "Rob" });
  });

  it("does not sign in when Keycloak refuses, the code cannot be exchanged, the account has no role, or storage fails", async () => {
    expect((await returning("error=access_denied&state=state-1")).headers.get("location")).toBe(`${APP}/login?error=refused`);

    vi.mocked(finishLogin).mockRejectedValueOnce(new Error("invalid_grant"));
    expect((await returning("code=code-1&state=state-1")).headers.get("location")).toBe(`${APP}/login?error=failed`);

    vi.mocked(finishLogin).mockResolvedValue(TOKENS);
    vi.mocked(identify).mockResolvedValueOnce(null);
    expect((await returning("code=code-1&state=state-1")).headers.get("location")).toBe(`${APP}/login?error=account`);

    vi.mocked(identify).mockResolvedValue(ROB);
    storage.mockResolvedValueOnce(new Response(null, { status: 500 }));
    const failed = await returning("code=code-1&state=state-1");
    expect(failed.headers.get("location")).toBe(`${APP}/login?error=storage`);
    expect(failed.cookies.get("session")).toBeUndefined();
  });

  it("does not exchange a code without the matching sign-in in progress", async () => {
    for (const response of [await returning("code=code-1&state=other"), await returning("code=code-1&state=state-1", null)]) {
      expect(response.headers.get("location")).toBe(`${APP}/login?error=expired`);
    }
    expect(finishLogin).not.toHaveBeenCalled();
  });
});

describe("/api/auth/logout", () => {
  it("ends the Keycloak session and clears the cookie", async () => {
    const cookie = `session=${await sealSession(TOKENS)}`;
    const response = await logout(request("/api/auth/logout", { method: "POST", cookie, headers: { origin: APP } }));

    expect(response.status).toBe(204);
    expect(endSession).toHaveBeenCalledWith(expect.objectContaining({ refresh: "refresh-1" }), undefined);
    expect(response.headers.get("set-cookie")).toMatch(/^session=;/);
  });

  it("signs out here even when Keycloak cannot be reached", async () => {
    vi.mocked(endSession).mockRejectedValue(new Error("discovery failed"));
    const cookie = `session=${await sealSession(TOKENS)}`;
    const response = await logout(request("/api/auth/logout", { method: "POST", cookie }));

    expect(response.status).toBe(204);
    expect(response.headers.get("set-cookie")).toMatch(/^session=;/);
  });

  it("refuses other sites", async () => {
    const cookie = `session=${await sealSession(TOKENS)}`;
    const response = await logout(request("/api/auth/logout", { method: "POST", cookie, headers: { origin: "https://evil.example" } }));

    expect(response.status).toBe(403);
    expect(endSession).not.toHaveBeenCalled();
    expect(response.headers.get("set-cookie")).toBeNull();
  });
});

describe("/api/auth/session", () => {
  it("is the signed-in person, never cached", async () => {
    vi.mocked(identify).mockResolvedValue(ROB);
    const response = await session(request("/api/auth/session", { cookie: `session=${await sealSession(TOKENS)}` }));

    expect(await response.json()).toEqual(ROB);
    expect(response.headers.get("cache-control")).toBe("private, no-store");
  });

  it("is null without a session", async () => {
    const response = await session(request("/api/auth/session"));
    expect(await response.json()).toBeNull();
  });
});
