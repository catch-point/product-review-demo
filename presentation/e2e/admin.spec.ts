import { execFileSync } from "node:child_process";
import path from "node:path";

import { decodeJwt } from "jose";

import { forgeSession, readSession, sessionCookie } from "./support/session";
import { expect, test, type Account } from "./support/test";

const STACK = path.resolve(__dirname, "../../scripts/stack.sh");

/** scripts/stack.sh identity admin EMAIL: grants the realm's admin role. */
function makeAdmin(email: string): string {
  return execFileSync(STACK, ["identity", "admin", email], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

test("the admin role is granted with stack.sh, and gives nothing more yet", async ({ signUp, baseURL }) => {
  const olive = await signUp("owner", "Olive Owner");
  const ada = await signUp("reviewer", "Ada Admin");
  const idOf = async ({ page }: Account) =>
    ((await (await page.request.get("/api/storage/people/me")).json()).id as string).replace(/.*\//, "");
  const profile = `/api/storage/people/${await idOf(olive)}`;
  const roles = async ({ context }: Account) =>
    decodeJwt((await readSession((await sessionCookie(context, baseURL!))!)).access).roles as string[] | undefined;

  await test.step("a new account has no admin role, and cannot read another person's email", async () => {
    expect(await roles(ada)).not.toContain("admin");
    expect(await (await ada.page.request.get(profile)).json()).not.toHaveProperty("email");
  });

  await test.step("stack.sh grants the role, only to an account that exists", async () => {
    expect(makeAdmin(ada.email)).toContain(`${ada.email} is an administrator`);
    expect(makeAdmin("nobody-here@example.com")).not.toContain("is an administrator");
  });

  let etag = "";
  await test.step("the renewed token carries it, next to the reviewer role", async () => {
    // As if the access token had expired, so the next request renews it.
    const cookie = (await sessionCookie(ada.context, baseURL!))!;
    await forgeSession(ada.context, cookie, { ...(await readSession(cookie)), expiresAt: 0 });
    const read = await ada.page.request.get(profile);
    const claims = decodeJwt((await readSession((await sessionCookie(ada.context, baseURL!))!)).access);
    expect(claims.roles).toContain("admin");
    expect(claims.role).toBe("reviewer");
    expect(await read.json()).not.toHaveProperty("email");
    expect(read.headers()["cache-control"]).toBe("private, no-store");
    etag = read.headers().etag;
  });

  await test.step("only /people/me shows the caller's email, privately", async () => {
    const again = await ada.page.request.get(profile);
    expect(again.headers()["cache-control"]).toBe("no-cache");
    expect(again.headers().vary ?? "").not.toMatch(/cookie|authorization/i);
    const me = await ada.page.request.get("/api/storage/people/me");
    expect((await me.json()).email).toBe(ada.email);
    expect(me.headers()["cache-control"]).toMatch(/private/);
    expect(me.headers().vary).toMatch(/cookie/i);
  });

  await test.step("an administrator changes nothing of anyone else's", async () => {
    const put = await ada.page.request.put(profile, {
      headers: { "content-type": "application/ld+json", "if-match": etag },
      data: JSON.stringify({ type: "Person", name: "Changed" }),
    });
    expect(put.status()).toBe(403);
    const add = await ada.page.request.post("/api/storage/products", {
      headers: { "content-type": "application/ld+json" },
      data: JSON.stringify({ type: "Product", name: "Admin's" }),
    });
    expect(add.status()).toBe(403);
  });

  await test.step("others still cannot read the administrator's email", async () => {
    const adaProfile = `/api/storage/people/${await idOf(ada)}`;
    expect(await (await olive.page.request.get(adaProfile)).json()).not.toHaveProperty("email");
  });
});
