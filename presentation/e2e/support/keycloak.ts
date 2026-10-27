import type { Page } from "@playwright/test";

/** Registering and signing in on Keycloak's pages, with the verification email from Mailpit. */

export const KEYCLOAK_URL = process.env.E2E_KEYCLOAK_URL ?? "http://localhost:8180";
export const MAILPIT_URL = process.env.E2E_MAILPIT_URL ?? "http://localhost:8025";

/** Keycloak's pages. */
export const KEYCLOAK = (url: URL) => url.origin === new URL(KEYCLOAK_URL).origin;

export type Role = "reviewer" | "owner";

/** The link in the verification email Keycloak sent to `email`. */
export async function verificationLink(email: string): Promise<string> {
  for (let i = 0; i < 40; i++) {
    const search = `${MAILPIT_URL}/api/v1/search?query=${encodeURIComponent(`to:${email}`)}`;
    const list = (await (await fetch(search)).json()) as { messages?: { ID: string }[] };
    if (list.messages?.length) {
      const message = (await (await fetch(`${MAILPIT_URL}/api/v1/message/${list.messages[0].ID}`)).json()) as {
        Text: string;
      };
      const link = message.Text.match(/https?:\/\/\S+action-token\S+/)?.[0];
      if (link) {
        return link;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`No verification email for ${email}`);
}

/** On Keycloak's registration page: fill it in, then follow the emailed link. */
export async function register(
  page: Page,
  { role, name, email, password = "password123" }: { role: Role; name: string; email: string; password?: string },
): Promise<void> {
  await page.waitForURL(KEYCLOAK);
  await page.locator(`#role-${role}`).check();
  await page.locator("#firstName").fill(name);
  await page.locator("#email").fill(email);
  await page.locator("#password").fill(password);
  await page.locator("#password-confirm").fill(password);
  await page.locator("#kc-register-form [type=submit]").click();
  await page.getByText(/verify your email/i).first().waitFor();
  await page.goto(await verificationLink(email));
}

/** On Keycloak's sign-in page. */
export async function signIn(page: Page, email: string, password = "password123"): Promise<void> {
  await page.waitForURL(KEYCLOAK);
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
}
