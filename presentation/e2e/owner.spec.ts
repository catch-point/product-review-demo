import path from "node:path";

import { KEYCLOAK, signIn } from "./support/keycloak";
import { expect, test } from "./support/test";

test("an owner adds, edits and discontinues a product, and signs out and in", async ({ signUp, errors, allowErrors, baseURL }) => {
  const { page, email } = await signUp("owner", "Oscar Owner");
  const main = page.locator("main");
  const form = page.locator("form:visible");
  // Owners only ever see their own products, which storage chooses from their token.
  page.on("request", (request) => {
    if (request.url().includes("owner=")) errors.push(`owner filter used: ${request.url()}`);
  });
  page.on("dialog", (dialog) => dialog.accept());
  const name = `E2E Headphones ${Date.now() % 100000}`;

  await test.step("registering as an owner lands on My products, empty", async () => {
    await page.waitForURL("/my-products");
    await expect(page.getByText("You have not added a product yet.")).toBeVisible();
    await expect(page.locator("nav").getByRole("link", { name: "Add Product" })).toBeVisible();
  });

  await test.step("owners opening the listing are sent to My products", async () => {
    await page.goto("/");
    await page.waitForURL("/my-products");
  });

  let detailsUrl = "";
  await test.step("adding a product shows it, with its image and no review form", async () => {
    await main.getByRole("link", { name: "Add product", exact: true }).first().click();
    await page.waitForURL("/products/new");
    await form.getByLabel("Product name").fill(name);
    await form.getByLabel("Description").fill("Wireless headphones with active noise cancellation.");
    await form.getByLabel("Audio").check();
    await form.getByLabel("Brand").fill("Aria");
    await form.getByLabel("GTIN").fill("00012345600012");
    await form.getByLabel("SKU").fill("ARIA-100");
    await form.getByLabel("MPN").fill("ARIA-100-BLK");
    await form.getByLabel("Image").setInputFiles(path.join(__dirname, "fixtures/red.png"));
    await page.getByRole("button", { name: "Create product" }).click();
    await page.waitForURL(/\/products\/\d+$/);
    await expect(main.getByRole("heading", { name, level: 2 })).toBeVisible();
    detailsUrl = page.url();
    await expect(main.getByText("Aria · Audio")).toBeVisible();
    await expect(main.getByText("00012345600012")).toBeVisible();
    await expect(main.getByText("ARIA-100-BLK")).toBeVisible();
    await expect(main.locator(`img[alt="${name}"]`)).toHaveAttribute("src", /\/api\/storage\/images\//);
    await expect(main.getByLabel("Have you used this product? What did you think?")).toHaveCount(0);
  });

  await test.step("the product replaced the form in history: Back goes to My products", async () => {
    await page.goBack();
    await page.waitForURL("/my-products");
    await expect(main.getByRole("heading", { name, level: 2 })).toBeVisible();
    await page.goForward();
    await page.waitForURL(detailsUrl);
  });

  await test.step("editing it saves, and goes back rather than pushing", async () => {
    await main.getByRole("link", { name: "Edit" }).click();
    await page.waitForURL(/\/edit$/);
    await form.getByLabel("Product name").fill(`${name} Renamed`);
    await form.getByLabel("Home").check();
    const historyBefore = await page.evaluate(() => history.length);
    await page.getByRole("button", { name: "Save changes" }).click();
    await page.waitForURL(detailsUrl);
    await expect(main.getByRole("heading", { name: `${name} Renamed`, level: 2 })).toBeVisible();
    await expect(main.getByText("Aria · Audio · Home")).toBeVisible();
    expect(await page.evaluate(() => history.length)).toBeLessThanOrEqual(historyBefore);
  });

  await test.step("reviewers cannot edit or delete it", async () => {
    allowErrors({ status: 403, url: /^\/api\/storage\/products\/\d+$/ });
    const id = detailsUrl.split("/").pop();
    const reviewer = await signUp("reviewer", "Rhea Reviewer");
    await reviewer.page.goto(`/products/${id}/edit`);
    await reviewer.page.waitForURL("/");
    const status = await reviewer.page.evaluate(async (id) => {
      const response = await fetch(`/api/storage/products/${id}`, { method: "DELETE", headers: { "If-Match": "*" } });
      return response.status;
    }, id);
    expect(status).toBe(403);
  });

  await test.step("discontinuing it from My products removes it", async () => {
    await page.getByRole("link", { name: "My Products" }).click();
    await page.waitForURL("/my-products");
    await main.locator("article", { hasText: `${name} Renamed` }).getByRole("button", { name: "Discontinue" }).click();
    await expect(page.getByText("You have not added a product yet.")).toBeVisible();
  });

  await test.step("signing out ends the Keycloak session too", async () => {
    await page.getByRole("button", { name: "Sign out" }).click();
    await page.waitForURL(KEYCLOAK);
    await expect(page.locator("#password")).toBeVisible();
    expect(await (await page.request.get("/api/auth/session")).text()).toBe("null");
  });

  await test.step("an owner's page asks to sign in, and signing in returns to it", async () => {
    await page.goto("/products/new");
    await signIn(page, email, "wrong-password");
    await expect(page.getByText(/invalid/i).first()).toBeVisible();
    await signIn(page, email);
    await page.waitForURL("/products/new");
  });

  await test.step("Back onto Keycloak's page signs straight in again, into the application", async () => {
    await page.goBack();
    await page.waitForURL((url) => !KEYCLOAK(url));
    const landed = new URL(page.url());
    expect(landed.origin).toBe(new URL(baseURL!).origin);
    expect(landed.pathname).not.toMatch(/^\/(login|api\/)/);
    expect((await (await page.request.get("/api/auth/session")).json())?.role).toBe("owner");
  });

  await test.step("no horizontal scroll at phone width", async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/my-products");
    await expect(page.getByRole("heading", { name: "My products" })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
  });
});
