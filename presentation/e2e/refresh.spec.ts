import { forgeSession, readSession, sessionCookie } from "./support/session";
import { expect, test } from "./support/test";

test("an expired access token is renewed with the refresh token", async ({ signUp, baseURL }) => {
  const { context, page, email } = await signUp("reviewer", "Rene Refresh");
  const cookie = async () => (await sessionCookie(context, baseURL!))!;
  const original = await cookie();
  const tokens = await readSession(original);
  // As if the access token had expired.
  const expire = () => forgeSession(context, original, { ...tokens, expiresAt: 0 });

  await test.step("the session cookie holds the tokens, encrypted, for this site only", async () => {
    expect(original.httpOnly).toBe(true);
    expect(original.sameSite).toBe("Lax");
    expect(typeof tokens.access).toBe("string");
    expect(typeof tokens.refresh).toBe("string");
  });

  await test.step("reading the session renews the token, and rewrites the cookie", async () => {
    const expired = await expire();
    expect((await (await page.request.get("/api/auth/session")).json())?.email).toBe(email);
    const renewed = await cookie();
    expect(renewed.value).not.toBe(expired);
    const after = await readSession(renewed);
    expect(after.access).not.toBe(tokens.access);
    expect(after.expiresAt).toBeGreaterThan(Date.now() / 1000);
  });

  await test.step("the proxy renews it too, before calling storage", async () => {
    await expire();
    const me = await page.request.get("/api/storage/people/me");
    expect(me.status()).toBe(200);
    expect((await me.json()).email).toBe(email);
  });

  await test.step("a response that renews the cookie is never cached", async () => {
    const product: string = (await (await page.request.get("/api/storage/products?limit=1")).json()).member[0].id;
    const plain = await page.request.get(product);
    expect(plain.headers()["cache-control"]).toBe("no-cache");
    expect(plain.headers()["set-cookie"]).toBeUndefined();
    await forgeSession(context, await cookie(), { ...(await readSession(await cookie())), expiresAt: 0 });
    const renewing = await page.request.get(product);
    expect(renewing.headers()["set-cookie"]).toContain("session=");
    expect(renewing.headers()["cache-control"]).toBe("private, no-store");
  });

  await test.step("a refresh token Keycloak no longer honours ends the session", async () => {
    await forgeSession(context, original, { ...tokens, expiresAt: 0, refresh: "not-a-refresh-token" });
    expect(await (await page.request.get("/api/auth/session")).text()).toBe("null");
    expect(await sessionCookie(context, baseURL!)).toBeUndefined();
  });
});
