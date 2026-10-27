import type { Page } from "@playwright/test";

import { expect, test, watch } from "./support/test";

/** The review form, on a product's page. */
function reviewForm(page: Page) {
  return {
    text: page.getByPlaceholder("Write your review..."),
    rating: page.getByLabel("Rating"),
    submit: page.getByRole("button", { name: "Submit review" }),
    update: page.getByRole("button", { name: "Update review" }),
    remove: page.getByRole("button", { name: "Delete review" }),
  };
}

test("a reviewer reviews a product once, then updates or deletes that review", async ({ signUp, errors, allowErrors }) => {
  const { context, page } = await signUp("reviewer", "Olga Once");
  // Any of the first 200 products, so runs seldom pick the same one.
  const total: number = (await (await page.request.get("/api/storage/products?limit=1")).json()).totalItems;
  const offset = Math.floor(Math.random() * Math.min(total, 200));
  const listing = await (await page.request.get(`/api/storage/products?limit=1&offset=${offset}`)).json();
  const product: { id: string } = listing.member[0];
  const id = product.id.replace(/.*\//, "");
  const rating = async () => (await (await page.request.get(product.id)).json()).aggregateRating ?? { ratingCount: 0 };
  const mine = page.locator("main article").filter({ hasText: "By Olga Once" });
  const open = async (p: Page) => {
    await p.goto(`/products/${id}`);
    await expect(p.getByRole("button", { name: /Submit review|Update review/ })).toBeVisible();
  };
  const before = await rating();
  const form = reviewForm(page);

  await open(page);
  await expect(form.submit).toBeVisible();
  await expect(form.remove).toBeHidden();

  // A second tab, opened now, still shows the empty form.
  const other = await context.newPage();
  watch(other, errors);
  await open(other);
  const stale = reviewForm(other);

  let posted = before;
  await test.step("posting it makes the form their review", async () => {
    await form.text.fill("Good sound, weak bass.");
    await form.rating.selectOption("4");
    await form.submit.click();
    await expect(page.getByText("Thank you! Your review is posted.")).toBeVisible();
    await expect(form.update).toBeVisible();
    await expect(page.getByText("Your review", { exact: true })).toBeVisible();
    await expect(form.text).toHaveValue("Good sound, weak bass.");
    await expect(form.rating).toHaveValue("4");
    await expect(mine).toHaveCount(1);
    posted = await rating();
    expect(posted.ratingCount).toBe(before.ratingCount + 1);
  });

  let updated = posted;
  await test.step("updating replaces it, changing the average but not the count", async () => {
    await form.text.fill("Good sound, and the bass has improved.");
    await form.rating.selectOption("5");
    await form.update.click();
    await expect(page.getByText("Your review is updated.")).toBeVisible();
    await expect(page.getByText(/updated on \d\d\/\d\d\/\d{4}/)).toBeVisible();
    await expect(form.rating).toHaveValue("5");
    await expect(page.locator("main article").filter({ hasText: "the bass has improved" })).toBeVisible();
    await expect(mine).toHaveCount(1);
    updated = await rating();
    expect(updated.ratingCount).toBe(posted.ratingCount);
    expect(updated.ratingValue).toBeGreaterThanOrEqual(posted.ratingValue);
  });

  await test.step("after a reload the form is still their review", async () => {
    await open(page);
    await expect(form.update).toBeVisible();
    await expect(form.text).toHaveValue("Good sound, and the bass has improved.");
  });

  await test.step("a second review, from the stale tab, is refused and shows the first", async () => {
    allowErrors({ status: 409, url: /^\/api\/storage\/products\/\d+\/reviews$/ });
    await stale.text.fill("A second opinion");
    await stale.submit.click();
    await expect(other.getByText("You have already reviewed this product. Here is your review: update it instead.")).toBeVisible();
    await expect(stale.update).toBeVisible();
    await expect(stale.text).toHaveValue("Good sound, and the bass has improved.");
    expect((await rating()).ratingCount).toBe(updated.ratingCount);
  });

  await test.step("an update from a stale tab is refused, and shows the newer version", async () => {
    allowErrors({ status: 412, url: /^\/api\/storage\/reviews\/\d+$/ });
    await form.text.fill("Changed in the first tab");
    await form.update.click();
    await expect(page.getByText("Your review is updated.")).toBeVisible();
    await stale.text.fill("Changed in the second tab");
    await stale.update.click();
    await expect(other.getByText(/Someone else changed this since you opened it/)).toBeVisible();
    await expect(stale.text).toHaveValue("Changed in the first tab");
  });

  await test.step("deleting it brings back an empty form, and the rating no longer counts it", async () => {
    page.once("dialog", (dialog) => dialog.accept());
    await form.remove.click();
    await expect(page.getByText("Your review is deleted.")).toBeVisible();
    await expect(form.submit).toBeVisible();
    await expect(form.text).toHaveValue("");
    await expect(form.remove).toBeHidden();
    await expect(mine).toHaveCount(0);
    expect((await rating()).ratingCount).toBe(before.ratingCount);
  });

  await test.step("the proxy passes the author filter", async () => {
    const me = await (await page.request.get("/api/storage/people/me")).json();
    const reviews = await page.request.get(`/api/storage/products/${id}/reviews?author=${encodeURIComponent(me.id)}`);
    expect((await reviews.json()).totalItems).toBe(0);
  });
});
