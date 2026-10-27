import { randomUUID } from "node:crypto";

import { test as base, expect, type BrowserContext, type Page } from "@playwright/test";

import { register, type Role } from "./keycloak";

export { expect };

export interface Account {
  context: BrowserContext;
  page: Page;
  email: string;
}

/** An email address no other run used (the time first, so runs sort in Mailpit and Keycloak). */
export function uniqueEmail(label: string): string {
  return `e2e-${label}-${Date.now()}-${randomUUID().slice(0, 8)}@example.com`;
}

/** A failed request a test provokes on purpose: its status, and the path it was for. */
export interface ExpectedError {
  status: number;
  url: RegExp;
}

// How watch() records a response the browser logged as failed.
const FAILED = /^HTTP (\d{3}) ([^\s,]+)/;

/**
 * Records what went wrong in a page: script errors (such as a hydration
 * mismatch), and every error it logged, among them each response it failed to
 * load, as "HTTP <status> <path>, on <page>". The errors fixture fails the
 * test on any of them it was not told to expect (allowErrors). Only Chrome's
 * ERR_NETWORK_CHANGED is left out: the host's network changed, as Docker
 * does, which is nothing the application did.
 */
export function watch(page: Page, errors: string[]): void {
  page.on("pageerror", (error) => errors.push(`${page.url()}: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() !== "error" || /net::ERR_NETWORK_CHANGED/.test(message.text())) {
      return;
    }
    const status = /status of (\d{3})\b/.exec(message.text())?.[1];
    const where = new URL(page.url()).pathname;
    errors.push(
      status
        ? `HTTP ${status} ${new URL(message.location().url).pathname}, on ${where}`
        : `${page.url()}: ${message.text()}`,
    );
  });
}

export const test = base.extend<{
  /** The errors in this test's pages, which must be none but those allowErrors expects. */
  errors: string[];
  /**
   * Failed requests this test provokes on purpose, by status and path: each
   * must happen (at least once), and is then not an error.
   */
  allowErrors: (...expected: ExpectedError[]) => void;
  expectedErrors: ExpectedError[];
  /** A new person, registered and signed in, in a browser of their own. */
  signUp: (role: Role, name: string) => Promise<Account>;
}>({
  errors: [
    async ({ page, expectedErrors }, provide, testInfo) => {
      const errors: string[] = [];
      watch(page, errors);
      await provide(errors);
      const happened = new Set<ExpectedError>();
      const unexpected = errors.filter((error) => {
        const [, status, path] = FAILED.exec(error) ?? [];
        const expected = expectedErrors.find((e) => String(e.status) === status && e.url.test(path));
        if (expected) {
          happened.add(expected);
        }
        return !expected;
      });
      expect(unexpected, "errors in the pages").toEqual([]);
      // Only once the test has done all it set out to: one that stopped early
      // has not provoked them yet.
      if (testInfo.status === testInfo.expectedStatus) {
        const missing = expectedErrors.filter((e) => !happened.has(e)).map((e) => `HTTP ${e.status} ${e.url}`);
        expect(missing, "expected errors that never happened (allowErrors)").toEqual([]);
      }
    },
    { auto: true },
  ],
  expectedErrors: async ({}, provide) => {
    await provide([]);
  },
  allowErrors: async ({ expectedErrors }, provide) => {
    await provide((...expected) => {
      expectedErrors.push(...expected);
    });
  },
  signUp: async ({ browser, baseURL, viewport, errors }, provide) => {
    const contexts: BrowserContext[] = [];
    await provide(async (role, name) => {
      const context = await browser.newContext({ baseURL, viewport });
      contexts.push(context);
      const page = await context.newPage();
      watch(page, errors);
      const email = uniqueEmail(role);
      await page.goto("/register");
      await register(page, { role, name, email });
      await page.waitForURL((url) => url.origin === new URL(baseURL!).origin && !url.pathname.startsWith("/api/"));
      return { context, page, email };
    });
    await Promise.all(contexts.map((context) => context.close()));
  },
});
