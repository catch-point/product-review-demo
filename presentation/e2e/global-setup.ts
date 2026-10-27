import type { FullConfig } from "@playwright/test";

import { KEYCLOAK_URL, MAILPIT_URL } from "./support/keycloak";

/**
 * The listed products the tests need: the reviewer scrolls past the 36th,
 * and review-once picks one of the first 200 (or all there are).
 */
const PRODUCTS = 40;

const SEED = "storage/scripts/seed-products.sh (with DEV_TOKENS=true in .env)";

/**
 * Before any test: the stack is up and seeded, or the run stops at once and
 * says what to do, rather than failing test by test minutes in. It does not
 * seed itself: that takes minutes, needs development tokens, and would fill
 * a developer's database unasked (CI seeds explicitly, .gitlab-ci.yml).
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0].use.baseURL!;
  const problems: string[] = [];

  async function get(url: string, fix: string): Promise<Response | undefined> {
    try {
      const response = await fetch(url, { headers: { Accept: "application/json" }, signal: AbortSignal.timeout(5000) });
      if (response.ok) {
        return response;
      }
      problems.push(`${url} answered ${response.status}: ${fix}`);
    } catch {
      problems.push(`${url} does not answer: ${fix}`);
    }
    return undefined;
  }

  const [products] = await Promise.all([
    get(`${baseURL}/api/storage/products?limit=1`, "scripts/stack.sh all up"),
    get(`${KEYCLOAK_URL}/realms/reviews/.well-known/openid-configuration`, "scripts/stack.sh identity up"),
    get(`${MAILPIT_URL}/api/v1/info`, "scripts/stack.sh identity up (Mailpit)"),
  ]);
  if (products) {
    const listed: number = (await products.json()).totalItems;
    if (listed < PRODUCTS) {
      problems.push(`${listed} products are listed, and the tests need ${PRODUCTS}: run ${SEED}`);
    }
  }
  if (problems.length > 0) {
    throw new Error(`The stack is not ready for the browser tests:\n- ${problems.join("\n- ")}`);
  }
}
