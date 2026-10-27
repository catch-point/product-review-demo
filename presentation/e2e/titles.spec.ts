import { expect, test } from "./support/test";

test("a product's pages are titled with its name", async ({ signUp, allowErrors }) => {
  const reviewer = (await signUp("reviewer", "Tia Reviewer")).page;

  await test.step("in the server-rendered HTML, before any script runs", async () => {
    // The listing's first product, whichever that is.
    const listing = await (await reviewer.request.get("/api/storage/products?limit=1")).json();
    const { id, name }: { id: string; name: string } = listing.member[0];
    const html = await (await reviewer.request.get(`/products/${id.replace(/.*\//, "")}`)).text();
    const escaped = name.replace(/&/g, "&amp;").replace(/'/g, "&#x27;");
    expect(html).toContain(`<title>${escaped} · Product Reviews • Portfolio Demo</title>`);
  });

  await test.step("navigating in the application, and Back and Forward", async () => {
    await reviewer.goto("/");
    const card = reviewer.locator("article").nth(3);
    const name = await card.locator("h2").innerText();
    await expect(reviewer).toHaveTitle("Products");
    await card.getByRole("link", { name: "Details" }).click();
    await reviewer.waitForURL(/\/products\/\d+$/);
    await expect(reviewer).toHaveTitle(`${name} · Product Reviews • Portfolio Demo`);
    await reviewer.goBack();
    await expect(reviewer).toHaveTitle("Products");
    await reviewer.goForward();
    await expect(reviewer).toHaveTitle(`${name} · Product Reviews • Portfolio Demo`);
  });

  await test.step("the edit page, with special characters intact", async () => {
    const owner = (await signUp("owner", "Tia Owner")).page;
    const created = await owner.request.post("/api/storage/products", {
      headers: { "content-type": "application/ld+json" },
      data: JSON.stringify({ type: "Product", name: "Title & Co's" }),
    });
    const iri: string = (await created.json()).id;
    const id = iri.replace(/.*\//, "");
    await owner.goto(`/products/${id}/edit`);
    await expect(owner).toHaveTitle("Edit Title & Co's · Product Reviews • Portfolio Demo");
    await owner.goto(`/products/${id}`);
    await expect(owner).toHaveTitle("Title & Co's · Product Reviews • Portfolio Demo");
    // Away from its page first, which would otherwise ask for what is gone.
    await owner.goto("/my-products");
    const etag = (await owner.request.get(iri)).headers().etag;
    await owner.request.delete(iri, { headers: { "if-match": etag } });
  });

  await test.step("an unknown product falls back to a generic title", async () => {
    allowErrors({ status: 404, url: /^\/api\/storage\/products\/999999$/ });
    await reviewer.goto("/products/999999");
    await expect(reviewer).toHaveTitle("Product · Product Reviews • Portfolio Demo");
  });
});
