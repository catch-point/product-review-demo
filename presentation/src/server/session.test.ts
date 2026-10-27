import { NextRequest, NextResponse } from "next/server";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import { installPreload } from "@/test/preload";

import { identify, refresh, type Tokens } from "./oidc";
import { seal, unseal } from "./sealed";
import { currentSession, sealSession, updateSessionCookie } from "./session";

// As the server runs: with client-address.cjs, whose forwarding headers it believes.
beforeAll(installPreload);

vi.mock("./oidc", () => ({ identify: vi.fn(), refresh: vi.fn() }));

const ROB = { email: "rob@example.com", name: "Rob", role: "reviewer" } as const;
const SECRET = "a-test-session-secret-of-at-least-32-characters";
const now = () => Math.floor(Date.now() / 1000);

function requestWith(cookie?: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest("http://localhost:3000/api/auth/session", {
    headers: { ...headers, ...(cookie ? { cookie: `session=${cookie}` } : {}) },
  });
}

afterEach(() => {
  vi.resetAllMocks();
});

describe("currentSession", () => {
  it("is nobody without a session cookie, and leaves the cookie alone", async () => {
    expect(await currentSession(requestWith())).toEqual({ user: null, cookie: undefined });
    expect(identify).not.toHaveBeenCalled();
  });

  it("clears a cookie it cannot read: forged, sealed with another secret, or expired", async () => {
    const tokens = { access: "a", refresh: "r", expiresAt: now() + 300 };
    for (const cookie of ["forged", await seal(tokens, "another-secret-another-secret-0123456789", 60), await seal(tokens, SECRET, -1)]) {
      expect(await currentSession(requestWith(cookie))).toEqual({ user: null, cookie: null });
    }
    expect(identify).not.toHaveBeenCalled();
  });

  it("is the person the access token names, while it is valid", async () => {
    vi.mocked(identify).mockResolvedValue(ROB);
    const cookie = await sealSession({ access: "access-1", refresh: "refresh-1", expiresAt: now() + 300 });

    expect(await currentSession(requestWith(cookie))).toEqual({ user: ROB, accessToken: "access-1", cookie: undefined });
    expect(identify).toHaveBeenCalledWith("access-1");
    expect(refresh).not.toHaveBeenCalled();
  });

  it("renews the tokens before they expire, for the client, and reseals the cookie", async () => {
    const old: Tokens = { access: "access-1", refresh: "refresh-1", expiresAt: now() + 10 };
    const renewed: Tokens = { access: "access-2", refresh: "refresh-2", expiresAt: now() + 300 };
    vi.mocked(refresh).mockResolvedValue(renewed);
    vi.mocked(identify).mockResolvedValue(ROB);

    const session = await currentSession(requestWith(await sealSession(old), { "X-Forwarded-For": "203.0.113.7" }));

    expect(refresh).toHaveBeenCalledWith(expect.objectContaining(old), "203.0.113.7");
    expect(identify).toHaveBeenCalledWith("access-2");
    expect(session).toMatchObject({ user: ROB, accessToken: "access-2" });
    expect(await unseal<Tokens>(session.cookie!, SECRET)).toMatchObject(renewed);
  });

  it("ends when the tokens cannot be renewed", async () => {
    vi.mocked(refresh).mockRejectedValue(new Error("invalid_grant"));
    const cookie = await sealSession({ access: "access-1", refresh: "refresh-1", expiresAt: now() - 60 });

    expect(await currentSession(requestWith(cookie))).toEqual({ user: null, cookie: null });
    expect(identify).not.toHaveBeenCalled();
  });

  it("ends when the access token no longer names anyone", async () => {
    vi.mocked(identify).mockResolvedValue(null);
    const cookie = await sealSession({ access: "access-1", refresh: "refresh-1", expiresAt: now() + 300 });

    expect(await currentSession(requestWith(cookie))).toEqual({ user: null, cookie: null });
  });
});

describe("updateSessionCookie", () => {
  it("sets a new session cookie, for the browser only", () => {
    const response = NextResponse.json({});
    updateSessionCookie(response, requestWith(), "sealed");
    const cookie = response.headers.get("set-cookie")!;
    expect(cookie).toMatch(/^session=sealed;/);
    expect(cookie).toMatch(/HttpOnly/i);
    expect(cookie).toMatch(/SameSite=lax/i);
    expect(cookie).toMatch(/Max-Age=36000/);
    expect(cookie).not.toMatch(/Secure/i);
    expect(response.headers.get("cache-control")).toBe("private, no-store");
  });

  it("marks it Secure behind an HTTPS proxy", () => {
    const response = NextResponse.json({});
    updateSessionCookie(response, requestWith(undefined, { "x-forwarded-proto": "https" }), "sealed");
    expect(response.headers.get("set-cookie")).toMatch(/Secure/i);
  });

  it("clears it, or leaves it", () => {
    const cleared = NextResponse.json({});
    updateSessionCookie(cleared, requestWith(), null);
    expect(cleared.headers.get("set-cookie")).toMatch(/^session=;.*Max-Age=0/);

    const left = NextResponse.json({}, { headers: { "Cache-Control": "no-cache" } });
    updateSessionCookie(left, requestWith(), undefined);
    expect(left.headers.get("set-cookie")).toBeNull();
    expect(left.headers.get("cache-control")).toBe("no-cache");
  });
});
