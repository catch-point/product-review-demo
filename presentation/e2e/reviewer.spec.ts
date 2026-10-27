import { KEYCLOAK, register } from "./support/keycloak";
import { expect, test, uniqueEmail } from "./support/test";

test("a reviewer registers, browses, reviews and filters", async ({ page }) => {
  const cards = page.locator("article");
  const email = uniqueEmail("reviewer");

  await test.step("anonymous people are sent to Keycloak's sign-in page", async () => {
    await page.goto("/");
    await page.waitForURL(KEYCLOAK);
    expect(new URL(page.url()).pathname).toMatch(/\/protocol\/openid-connect\/auth$/);
  });

  await test.step("registering from there lands on the listing, signed in", async () => {
    await page.locator("#kc-info").getByRole("link", { name: "Register" }).click();
    await register(page, { role: "reviewer", name: "Eddie Reviewer", email });
    await page.waitForURL("/");
    await expect(cards.first()).toBeVisible();
    await expect(page.getByText("Eddie Reviewer")).toBeVisible();
  });

  let loaded = 0;
  await test.step("the listing shows 12 products, and more as it scrolls", async () => {
    await expect(cards).toHaveCount(12);
    await page.mouse.wheel(0, 20000);
    await expect.poll(() => cards.count()).toBeGreaterThan(12);
    await page.mouse.wheel(0, 20000);
    await expect.poll(() => cards.count(), { timeout: 15_000 }).toBeGreaterThanOrEqual(36);
    loaded = await cards.count();
  });

  let name = "";
  let detailsUrl = "";
  let scrollBefore = 0;
  await test.step("opening a product from deep in the list pushes its details", async () => {
    const target = cards.nth(30);
    await target.scrollIntoViewIfNeeded();
    name = await target.locator("h2").innerText();
    scrollBefore = await page.evaluate(() => window.scrollY);
    const historyBefore = await page.evaluate(() => history.length);
    await target.getByRole("link", { name: "Details" }).click();
    await page.waitForURL(/\/products\/\d+$/);
    await expect(page.getByRole("heading", { name, level: 2 })).toBeVisible();
    detailsUrl = page.url();
    expect(await page.evaluate(() => history.length)).toBe(historyBefore + 1);

    const reviews = page.locator("main article");
    const none = page.getByText("No reviews yet.");
    await expect(reviews.or(none).first()).toBeVisible();
    if (!(await none.isVisible())) {
      await expect(reviews.first()).toContainText(/By \S+.* on \d\d\/\d\d\/\d{4}/);
    }
  });

  await test.step("Back keeps the loaded pages and the scroll position", async () => {
    await page.goBack();
    await page.waitForURL("/");
    await expect(cards.nth(30)).toBeAttached();
    expect(await cards.count()).toBeGreaterThanOrEqual(loaded);
    await expect.poll(async () => Math.abs((await page.evaluate(() => window.scrollY)) - scrollBefore)).toBeLessThan(50);
  });

  await test.step("Forward returns to the details", async () => {
    await page.goForward();
    await page.waitForURL(detailsUrl);
    await expect(page.getByRole("heading", { name, level: 2 })).toBeVisible();
  });

  await test.step("reviewing it lists the review with its author", async () => {
    const text = `Clear sound and comfortable to wear. (e2e ${email})`;
    await page.getByLabel("Have you used this product? What did you think?").fill(text);
    await page.getByLabel("Rating", { exact: true }).selectOption("4");
    await page.getByRole("button", { name: "Submit review" }).click();
    await expect(page.getByText("Thank you! Your review is posted.")).toBeVisible();
    await expect(page.locator("article", { hasText: text }).getByText("Eddie Reviewer")).toBeVisible();
  });

  await test.step("filters go into the URL, replacing the history entry", async () => {
    await page.goto("/");
    await expect(cards.first()).toBeVisible();
    const historyBefore = await page.evaluate(() => history.length);
    await page.getByLabel("Category").selectOption("Audio");
    await page.waitForURL(/category=Audio/);
    await page.getByLabel("Minimum rating").selectOption("4");
    await page.waitForURL(/minRating=4/);
    await expect
      .poll(async () => {
        const categories = await page.locator("article p.text-xs").allInnerTexts();
        return categories.length > 0 && categories.every((line) => line.includes("Audio"));
      })
      .toBe(true);
    expect(await page.evaluate(() => history.length)).toBe(historyBefore);

    await page.reload();
    await expect(cards.or(page.getByText("No products match")).first()).toBeVisible();
    await expect(page.getByLabel("Category")).toHaveValue("Audio");
    await expect(page.getByLabel("Minimum rating")).toHaveValue("4");
  });

  await test.step("reviewers opening an owner's page are sent home", async () => {
    await page.goto("/my-products");
    await page.waitForURL("/");
  });
});
