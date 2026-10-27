import type { BrowserContext, Cookie } from "@playwright/test";

import { seal, unseal } from "../../src/server/sealed";

/** SESSION_SECRET, from the repository root's .env (playwright.config.ts loads it). */
const SECRET = (() => {
  const secret = process.env.SESSION_SECRET;
  if (!secret) throw new Error("SESSION_SECRET is not set: playwright.config.ts loads the repository root's .env");
  return secret;
})();

/** What the cookie holds (src/server/oidc.ts Tokens). */
export interface Tokens {
  access: string;
  refresh?: string;
  expiresAt: number;
}

export async function sessionCookie(context: BrowserContext, baseURL: string): Promise<Cookie | undefined> {
  return (await context.cookies(baseURL)).find((cookie) => cookie.name === "session");
}

export async function readSession(cookie: Cookie): Promise<Tokens> {
  const tokens = await unseal<Tokens>(cookie.value, SECRET);
  if (!tokens) {
    throw new Error("Unreadable session cookie");
  }
  return tokens;
}

/** Replaces the session cookie with one holding `tokens`. */
export async function forgeSession(context: BrowserContext, cookie: Cookie, tokens: Tokens): Promise<string> {
  const value = await seal({ ...tokens }, SECRET, 3600);
  await context.addCookies([{ ...cookie, value }]);
  return value;
}
