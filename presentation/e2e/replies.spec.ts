import { expect, test, watch } from "./support/test";

test("the product's owner replies to a review once, then edits or deletes the reply", async ({ signUp, errors, allowErrors }) => {
  const { context, page: owner } = await signUp("owner", "Rita Replier");
  const reviewer = (await signUp("reviewer", "Ray Reviewer")).page;
  const rival = (await signUp("owner", "Otto Rival")).page;

  const created = await owner.request.post("/api/storage/products", {
    headers: { "content-type": "application/ld+json" },
    data: JSON.stringify({ type: "Product", name: `Reply Speaker ${Date.now() % 100000}` }),
  });
  const product = (await created.json()).id as string;
  const id = product.replace(/.*\//, "");
  const posted = await reviewer.request.post(`/api/storage/products/${id}/reviews`, {
    headers: { "content-type": "application/ld+json" },
    data: JSON.stringify({ type: "Review", reviewBody: "Clear sound.", reviewRating: { type: "Rating", ratingValue: 4 } }),
  });
  const review = (await posted.json()).id as string;
  const reply = (text: string) => ({
    headers: { "content-type": "application/ld+json" },
    data: JSON.stringify({ type: "Comment", text }),
  });
  const replies = async () => (await (await owner.request.get(review)).json()).comment ?? [];

  const item = owner.locator("main article", { hasText: "Clear sound." });
  const field = item.getByLabel("Your reply to Ray Reviewer");
  const button = (name: string) => item.getByRole("button", { name, exact: true });

  await test.step("reviewers, including the review's author, cannot reply", async () => {
    await reviewer.goto(`/products/${id}`);
    await expect(reviewer.locator("main article", { hasText: "Clear sound." })).toBeVisible();
    await expect(reviewer.getByRole("button", { name: "Reply" })).toHaveCount(0);
    expect((await reviewer.request.post(`${review}/comments`, reply("Thanks!"))).status()).toBe(403);
  });

  await test.step("nor can the owner of another product", async () => {
    await rival.goto(`/products/${id}`);
    await expect(rival.locator("main article", { hasText: "Clear sound." })).toBeVisible();
    await expect(rival.getByRole("button", { name: "Reply" })).toHaveCount(0);
    expect((await rival.request.post(`${review}/comments`, reply("Come to mine"))).status()).toBe(403);
  });

  // A second tab, opened now, still offers to reply.
  const stale = await context.newPage();
  watch(stale, errors);
  await stale.goto(`/products/${id}`);
  const staleItem = stale.locator("main article", { hasText: "Clear sound." });
  await expect(staleItem.getByRole("button", { name: "Reply", exact: true })).toBeVisible();

  await test.step("the owner replies, and the reply becomes theirs to edit or delete", async () => {
    await owner.goto(`/products/${id}`);
    await button("Reply").click();
    await expect(field).toBeFocused();
    await expect(button("Post reply")).toBeDisabled();
    await field.fill("Thank you, Ray. The bass is adjustable.");
    await button("Post reply").click();
    await expect(item.getByText("Your reply is posted.")).toBeVisible();
    await expect(item.getByText(/Rita Replier replied on \d\d\/\d\d\/\d{4}: Thank you, Ray\. The bass is adjustable\./)).toBeVisible();
    await expect(button("Reply")).toHaveCount(0);
    await expect(button("Edit reply")).toBeVisible();
    await expect(button("Delete reply")).toBeVisible();
  });

  await test.step("everyone sees the reply, but only the owner may change it", async () => {
    await reviewer.reload();
    await expect(reviewer.getByText("Rita Replier replied on")).toBeVisible();
    await expect(reviewer.getByRole("button", { name: /Edit reply|Delete reply/ })).toHaveCount(0);
  });

  await test.step("a second reply, from the stale tab, is refused and shows the first", async () => {
    allowErrors({ status: 409, url: /^\/api\/storage\/reviews\/\d+\/comments$/ });
    await staleItem.getByRole("button", { name: "Reply", exact: true }).click();
    await staleItem.getByLabel("Your reply to Ray Reviewer").fill("Thanks again!");
    await staleItem.getByRole("button", { name: "Post reply" }).click();
    await expect(staleItem.getByText("You have already replied to this review. Here is your reply: edit it instead.")).toBeVisible();
    await expect(staleItem.getByText("Thank you, Ray. The bass is adjustable.")).toBeVisible();
    await expect(staleItem.getByRole("button", { name: "Edit reply" })).toBeVisible();
    expect(await replies()).toHaveLength(1);
  });

  await test.step("editing it updates the reply", async () => {
    await button("Edit reply").click();
    await expect(field).toHaveValue("Thank you, Ray. The bass is adjustable.");
    await field.fill("Thank you, Ray. Try the new preset.");
    await button("Update reply").click();
    await expect(item.getByText("Your reply is updated.")).toBeVisible();
    await expect(item.getByText(/, updated on \d\d\/\d\d\/\d{4}: Thank you, Ray\. Try the new preset\./)).toBeVisible();
  });

  await test.step("an edit from a stale tab is refused, and shows the newer version", async () => {
    allowErrors({ status: 412, url: /^\/api\/storage\/comments\/\d+$/ });
    await staleItem.getByRole("button", { name: "Edit reply" }).click();
    const staleField = staleItem.getByLabel("Your reply to Ray Reviewer");
    await expect(staleField).toHaveValue("Thank you, Ray. Try the new preset.");
    await button("Edit reply").click();
    await field.fill("Changed in the first tab");
    await button("Update reply").click();
    await expect(item.getByText("Your reply is updated.")).toBeVisible();
    await staleField.fill("Changed in the second tab");
    await staleItem.getByRole("button", { name: "Update reply" }).click();
    await expect(staleItem.getByText(/Someone else changed this since you opened it/)).toBeVisible();
    await expect(staleField).toHaveValue("Changed in the first tab");
  });

  await test.step("deleting it brings back Reply, and cancelling a reply posts nothing", async () => {
    owner.once("dialog", (dialog) => dialog.accept());
    await button("Delete reply").click();
    await expect(item.getByText("Your reply is deleted.")).toBeVisible();
    await expect(item.getByText("Changed in the first tab")).toHaveCount(0);
    expect(await replies()).toHaveLength(0);
    await button("Reply").click();
    await field.fill("Never mind");
    await item.getByRole("button", { name: "Cancel" }).click();
    await expect(field).toHaveCount(0);
    expect(await replies()).toHaveLength(0);
  });

});
