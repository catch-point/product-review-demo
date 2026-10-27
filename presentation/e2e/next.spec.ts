import { expect, test } from "./support/test";

// Signed in, /login and /register go straight to `next` (KeycloakHandoff),
// which must be a page of this site however it is written: the URL parser
// drops tabs and reads `\` as `/`, so these would otherwise be //evil.example.
test("a signed-in person's next page is always on this site", async ({ signUp, baseURL }) => {
  const { page } = await signUp("reviewer", "Nina Next");

  for (const next of ["/%5Cevil.example", "/%09/evil.example", "//evil.example"]) {
    await test.step(`/login?next=${next} stays here`, async () => {
      await page.goto(`/login?next=${next}`);
      await expect(page).toHaveURL(`${baseURL}/`);
    });
  }

  await test.step("a page of this site is kept", async () => {
    await page.goto(`/login?next=${encodeURIComponent("/about")}`);
    await expect(page).toHaveURL(`${baseURL}/about`);
  });
});
