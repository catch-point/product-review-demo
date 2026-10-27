import { randomBytes } from "node:crypto";
import { crc32, deflateSync } from "node:zlib";

import { KEYCLOAK } from "./support/keycloak";
import { expect, test } from "./support/test";

/**
 * A 4x4 PNG of random pixels, so its name (its SHA-256) is one no other run
 * had. (Storage strips anything after the image's end, and its metadata.)
 */
function randomPng(): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body));
    return Buffer.concat([length, body, crc]);
  };
  const size = 4;
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8; // bits per sample
  header[9] = 2; // RGB
  // Each row: filter type 0, then its pixels.
  const rows = Array.from({ length: size }, () => Buffer.concat([Buffer.alloc(1), randomBytes(size * 3)]));
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(Buffer.concat(rows))),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

// Run after the other journeys (playwright.config.ts): its burst uses up this
// address's allowance at the edge, which every test shares.

test("pages send the security headers, and not what serves them", async ({ request }) => {
  const page = await request.get("/");
  expect(page.headers()["content-security-policy"]).toContain("nonce-");
  expect(page.headers()["referrer-policy"]).toBe("strict-origin-when-cross-origin");
  expect(page.headers()["permissions-policy"]).toContain("geolocation=()");
  expect(page.headers()["x-content-type-options"]).toBe("nosniff");
  expect(page.headers()["x-powered-by"]).toBeUndefined();
});

test("pages load nothing from another host", async ({ page, signUp }) => {
  const app = new URL(test.info().project.use.baseURL!).host;
  const hosts = (on: typeof page) => {
    const seen = new Set<string>();
    on.on("request", (request) => seen.add(new URL(request.url()).host));
    return seen;
  };

  // Keycloak's sign-in page, which anonymous visitors are sent to.
  const signIn = hosts(page);
  await page.goto("/");
  await page.waitForURL(KEYCLOAK);
  await page.evaluate(() => document.fonts.ready);
  expect([...signIn].sort()).toEqual([app, new URL(page.url()).host].sort());

  // The application, signed in: its fonts (the stars' too) are its own.
  const { page: signedIn } = await signUp("reviewer", "Hal Host");
  const seen = hosts(signedIn);
  await signedIn.goto("/");
  await expect(signedIn.locator("article").first()).toBeVisible();
  await signedIn.evaluate(() => document.fonts.ready);
  expect([...seen]).toEqual([app]);
});

test("the edge caches what does not change, and limits each address", async ({ signUp, request }) => {
  const { page } = await signUp("owner", "Ivy Image");

  await test.step("an uploaded image is cached, for everyone", async () => {
    const png = randomPng();
    const upload = await page.request.post("/api/storage/images", {
      multipart: { file: { name: "red.png", mimeType: "image/png", buffer: png } },
    });
    expect(upload.status()).toBe(201);
    const image = new URL((await upload.json()).id as string).pathname;

    const first = await page.request.get(image);
    expect(first.headers()["x-cache-status"]).toBe("MISS");
    // Someone else, not signed in, gets the edge's copy.
    const again = await request.get(image);
    expect(again.headers()["x-cache-status"]).toBe("HIT");
    expect(await again.body()).toEqual(png);
  });

  await test.step("reads by people not signed in are kept a moment; signed-in ones are not", async () => {
    const statuses = [];
    for (let i = 0; i < 2; i++) {
      statuses.push((await request.get("/api/storage/categories")).headers()["x-cache-status"]);
    }
    expect(statuses).toContain("HIT");
    expect((await page.request.get("/api/storage/categories")).headers()["x-cache-status"]).toBe("BYPASS");
  });

  await test.step("a burst from one address is refused, whatever X-Forwarded-For it claims", async () => {
    const responses = await Promise.all(
      Array.from({ length: 300 }, (_, i) =>
        request.get("/api/storage/categories", { headers: { "X-Forwarded-For": `198.51.100.${i % 250}` } }),
      ),
    );
    const limited = responses.filter((r) => r.status() === 429);
    expect(limited.length).toBeGreaterThan(100);
    expect(limited[0].headers()["content-type"]).toBe("application/problem+json");
    expect(limited[0].headers()["retry-after"]).toBe("5");
    expect(await limited[0].json()).toMatchObject({ status: 429, title: "Too Many Requests" });
  });
});
