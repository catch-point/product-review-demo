import { expect, test } from "./support/test";

test("a discontinued product leaves the listing but its reviews stay", async ({ signUp, allowErrors }) => {
  const owner = (await signUp("owner", "Dana Discontinuer")).page;
  const rob = (await signUp("reviewer", "Rob Regular")).page;
  const eve = (await signUp("reviewer", "Eve Late")).page;

  const created = await owner.request.post("/api/storage/products", {
    headers: { "content-type": "application/ld+json" },
    data: JSON.stringify({
      type: "Product",
      name: `Retired Speaker ${Date.now() % 100000}`,
      description: "A speaker that is no longer made.",
      category: ["Audio"],
      brand: "Aria",
      gtin: "00012345600012",
      sku: "ARIA-200",
      mpn: "ARIA-200-BLK",
    }),
  });
  const product = (await created.json()).id as string;
  const id = product.replace(/.*\//, "");

  const listed = async () => {
    let url: string | undefined = "/api/storage/products?limit=100";
    while (url) {
      const page = await (await eve.request.get(url)).json();
      if (page.member.some((member: { id: string }) => member.id === product)) {
        return true;
      }
      url = page.view.next;
    }
    return false;
  };

  let review = "";
  await test.step("a reviewer reviews it while it is active", async () => {
    await expect.poll(listed).toBe(true);
    await rob.goto(`/products/${id}`);
    await rob.getByPlaceholder("Write your review...").fill("Still sounds great.");
    await rob.getByRole("button", { name: "Submit review" }).click();
    await expect(rob.getByText("Thank you! Your review is posted.")).toBeVisible();
    review = (await (await rob.request.get(`/api/storage/products/${id}/reviews`)).json()).member[0].id;
  });

  await test.step("its owner discontinues it, and it leaves the listing", async () => {
    const current = await owner.request.get(product);
    const deleted = await owner.request.delete(product, { headers: { "if-match": current.headers().etag } });
    expect(deleted.status()).toBe(204);
    expect((await owner.request.delete(product)).status()).toBe(204);
    expect((await owner.request.get(product)).status()).toBe(404);
    await expect.poll(listed).toBe(false);
    const me = await (await owner.request.get("/api/storage/people/me")).json();
    expect(me.owns ?? []).not.toContain(product);
  });

  await test.step("its reviews are no longer reachable through it", async () => {
    expect((await eve.request.get(`/api/storage/products/${id}/reviews`)).status()).toBe(404);
    const refused = await eve.request.post(`/api/storage/products/${id}/reviews`, {
      headers: { "content-type": "application/ld+json" },
      data: JSON.stringify({ type: "Review", reviewRating: { type: "Rating", ratingValue: 5 } }),
    });
    expect(refused.status()).toBe(404);
    allowErrors({ status: 404, url: /^\/api\/storage\/products\/\d+$/ });
    await eve.goto(`/products/${id}`);
    await expect(eve.getByText("There is no such product.")).toBeVisible();
  });

  await test.step("the review and its author's changes stay readable", async () => {
    const current = await rob.request.get(review);
    expect(current.status()).toBe(200);
    const body = await current.json();
    expect(body.reviewBody).toBe("Still sounds great.");
    const updated = await rob.request.put(review, {
      headers: { "content-type": "application/ld+json", "if-match": current.headers().etag },
      data: JSON.stringify({
        ...body,
        reviewBody: "Still sounds great, even though it is retired.",
        reviewRating: { type: "Rating", ratingValue: 5 },
      }),
    });
    expect(updated.status()).toBe(200);
    expect((await updated.json()).reviewBody).toBe("Still sounds great, even though it is retired.");
  });

  await test.step("the owner may still reply to the review", async () => {
    const reply = await owner.request.post(`${review}/comments`, {
      headers: { "content-type": "application/ld+json" },
      data: JSON.stringify({ type: "Comment", text: "Thank you for the review." }),
    });
    expect(reply.status()).toBe(201);
    expect((await (await rob.request.get(review)).json()).comment[0].text).toBe("Thank you for the review.");
  });
});
