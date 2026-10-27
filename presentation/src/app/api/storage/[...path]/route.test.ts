import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Session } from "@/lib/session";
import { identify, refresh } from "@/server/oidc";
import { sealSession } from "@/server/session";
import { DELETE, GET, POST, PUT } from "./route";

vi.mock("@/server/oidc", () => ({ identify: vi.fn(), refresh: vi.fn() }));

const APP = "http://localhost:3000";
const ROB: Session = { email: "rob@example.com", name: "Rob", role: "reviewer" };
const OLIVE: Session = { email: "olive@example.com", name: "Olive", role: "owner" };
const now = () => Math.floor(Date.now() / 1000);

let storage: ReturnType<typeof vi.fn<(url: string, init: RequestInit) => Promise<Response>>>;

beforeEach(() => {
  storage = vi.fn(async () =>
    Response.json({ id: `${APP}/api/storage/products/1` }, {
      headers: { "Content-Type": "application/ld+json", ETag: 'W/"1"', "Cache-Control": "no-cache", "X-Internal": "yes" },
    }),
  );
  vi.stubGlobal("fetch", storage);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

/** A session cookie for `user`, whose access token is `access-<role>`. */
async function signedIn(user: Session, expiresAt = now() + 300): Promise<string> {
  vi.mocked(identify).mockImplementation(async (token) => (token === `access-${user.role}` ? user : null));
  return `session=${await sealSession({ access: `access-${user.role}`, refresh: "refresh-1", expiresAt })}`;
}

function call(
  handler: typeof GET,
  path: string,
  {
    cookie,
    headers = {},
    ...init
  }: { cookie?: string; method?: string; headers?: Record<string, string>; body?: BodyInit } = {},
) {
  const url = new URL(`/api/storage/${path}`, APP);
  const request = new NextRequest(url, {
    ...init,
    ...(init.body instanceof ReadableStream ? { duplex: "half" } : {}),
    headers: { ...headers, ...(cookie ? { cookie } : {}) },
  });
  // The segments as Next.js passes them: split, then decoded (here before the URL drops any "..").
  const segments = path.split("?")[0].split("/").map(decodeURIComponent);
  return handler(request, { params: Promise.resolve({ path: segments }) } as RouteContext<"/api/storage/[...path]">);
}

/** What the storage service was asked. */
function upstream() {
  expect(storage).toHaveBeenCalledOnce();
  const [url, init] = storage.mock.calls[0];
  return { url, init, headers: new Headers(init.headers) };
}

describe("the storage proxy", () => {
  it("relays reads as the browser's request, without a token for anonymous people", async () => {
    const response = await call(GET, "products?category=Audio&limit=12");

    const { url, init, headers } = upstream();
    expect(url).toBe("http://localhost:8080/products?category=Audio&limit=12");
    expect(init.method).toBe("GET");
    expect(headers.get("authorization")).toBeNull();
    expect(headers.get("x-forwarded-proto")).toBe("http");
    expect(headers.get("x-forwarded-host")).toBe("localhost:3000");
    expect(headers.get("x-forwarded-prefix")).toBe("/api/storage");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ id: `${APP}/api/storage/products/1` });
    expect(response.headers.get("etag")).toBe('W/"1"');
    expect(response.headers.get("cache-control")).toBe("no-cache");
    expect(response.headers.get("x-internal")).toBeNull();
    expect(response.headers.get("content-security-policy")).toBeNull();
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("names the configured origin, whatever host the request claims", async () => {
    vi.stubEnv("PUBLIC_URL", "https://reviews.example/");
    try {
      await call(GET, "products", { headers: { host: "evil.example", "x-forwarded-host": "evil.example" } });
    } finally {
      vi.unstubAllEnvs();
    }

    const { headers } = upstream();
    expect(headers.get("x-forwarded-proto")).toBe("https");
    expect(headers.get("x-forwarded-host")).toBe("reviews.example");
    expect(headers.get("x-forwarded-prefix")).toBe("/api/storage");
  });

  it("relays the headers that keep an opened image from running as a page", async () => {
    const id = `${"a".repeat(64)}.png`;
    storage.mockResolvedValue(
      new Response(new Uint8Array([1, 2, 3]), {
        headers: {
          "Content-Type": "image/png",
          "Content-Disposition": `inline; filename="${id}"`,
          "Content-Security-Policy": "default-src 'none'; sandbox",
          "X-Content-Type-Options": "nosniff",
          ETag: `"${"a".repeat(64)}"`,
          "Cache-Control": "public, max-age=31536000, immutable",
          "X-Internal": "yes",
        },
      }),
    );

    const response = await call(GET, `images/${id}`);

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("image/png");
    expect(response.headers.get("content-disposition")).toBe(`inline; filename="${id}"`);
    expect(response.headers.get("content-security-policy")).toBe("default-src 'none'; sandbox");
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
    expect(response.headers.get("etag")).toBe(`"${"a".repeat(64)}"`);
    expect(response.headers.get("x-internal")).toBeNull();
  });

  it("sends the signed-in person's access token, and their request's body and conditions", async () => {
    storage.mockResolvedValue(new Response(null, { status: 201, headers: { Location: `${APP}/api/storage/reviews/7` } }));
    const body = JSON.stringify({ type: "Review", reviewRating: { type: "Rating", ratingValue: 4 } });

    const response = await call(POST, "products/1/reviews", {
      method: "POST",
      cookie: await signedIn(ROB),
      headers: { "Content-Type": "application/ld+json", Origin: APP, "If-None-Match": "*", "X-Other": "dropped" },
      body,
    });

    const { url, init, headers } = upstream();
    expect(url).toBe("http://localhost:8080/products/1/reviews");
    expect(init.method).toBe("POST");
    expect(headers.get("authorization")).toBe("Bearer access-reviewer");
    expect(headers.get("content-type")).toBe("application/ld+json");
    expect(headers.get("if-none-match")).toBe("*");
    expect(headers.get("cookie")).toBeNull();
    expect(headers.get("x-other")).toBeNull();
    expect(new TextDecoder().decode(init.body as ArrayBuffer)).toBe(body);
    expect(response.status).toBe(201);
    expect(response.headers.get("location")).toBe(`${APP}/api/storage/reviews/7`);
  });

  it("refuses what the person's role can never do, without asking storage", async () => {
    const reviewer = await signedIn(ROB);
    const created = await call(POST, "products", { method: "POST", cookie: reviewer, body: "{}" });
    expect(created.status).toBe(403);
    expect(created.headers.get("content-type")).toBe("application/problem+json");
    expect(await created.json()).toMatchObject({ status: 403, detail: "Only product owners can do this" });

    const owner = await signedIn(OLIVE);
    expect((await call(PUT, "reviews/7", { method: "PUT", cookie: owner, body: "{}" })).status).toBe(403);
    expect((await call(DELETE, "reviews/7", { method: "DELETE" })).status).toBe(401);
    expect(storage).not.toHaveBeenCalled();
  });

  it("proxies only the storage service's documents", async () => {
    for (const path of ["actuator/health", "openapi.yml", "people/rob", "products/1/..", "products/%2E%2E/actuator", "products/1%2F..%2F..%2Factuator"]) {
      const response = await call(GET, path);
      expect(response.status, path).toBe(404);
    }
    expect(storage).not.toHaveBeenCalled();
  });

  it("refuses bodies larger than storage accepts, without sending them", async () => {
    const owner = await signedIn(OLIVE);
    const large = "x".repeat(64 * 1024 + 1);

    // By their Content-Length, before reading the session.
    const declared = await call(POST, "products", {
      method: "POST",
      cookie: owner,
      headers: { "Content-Length": String(large.length) },
      body: large,
    });
    expect(declared.status).toBe(413);
    expect(declared.headers.get("content-type")).toBe("application/problem+json");
    expect(await declared.json()).toMatchObject({ status: 413, detail: "Request bodies may be at most 64 KB" });
    expect(identify).not.toHaveBeenCalled();

    // Without one (chunked), once reading passes the limit, which is never read further.
    let pulled = 0;
    const chunk = new TextEncoder().encode("x".repeat(16 * 1024));
    const chunked = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulled++;
        controller.enqueue(chunk);
      },
    });
    const streamed = await call(PUT, "products/1", { method: "PUT", cookie: owner, body: chunked });
    expect(streamed.status).toBe(413);
    expect(pulled).toBeLessThan(10);

    expect(storage).not.toHaveBeenCalled();
  });

  it("lets image uploads be larger, up to storage's image limit", async () => {
    const owner = await signedIn(OLIVE);
    const upload = new Uint8Array(1024 * 1024);
    expect((await call(POST, "images", { method: "POST", cookie: owner, body: upload })).status).toBe(200);
    expect(upstream().init.body).toHaveProperty("byteLength", upload.byteLength);

    const tooLarge = new Uint8Array(5 * 1024 * 1024 + 64 * 1024 + 1);
    const refused = await call(POST, "images", { method: "POST", cookie: owner, body: tooLarge });
    expect(refused.status).toBe(413);
    expect(await refused.json()).toMatchObject({ detail: "Images may be at most 5120 KB" });
  });

  it("refuses writes from other sites, before reading the session", async () => {
    const response = await call(POST, "products", {
      method: "POST",
      cookie: await signedIn(OLIVE),
      headers: { Origin: "https://elsewhere.example" },
      body: "{}",
    });
    expect(response.status).toBe(403);
    expect(await response.json()).toMatchObject({ detail: "Cross-site request refused" });
    expect(identify).not.toHaveBeenCalled();
    expect(storage).not.toHaveBeenCalled();
  });

  it("renews the session first, and then no cache may keep the response", async () => {
    const cookie = await signedIn(ROB, now() - 60);
    vi.mocked(refresh).mockResolvedValue({ access: "access-reviewer", refresh: "refresh-2", expiresAt: now() + 300 });

    const response = await call(GET, "products/1", { cookie });

    expect(upstream().headers.get("authorization")).toBe("Bearer access-reviewer");
    expect(response.headers.get("set-cookie")).toMatch(/^session=(?!;)/);
    expect(response.headers.get("cache-control")).toBe("private, no-store");
  });

  it("clears an ended session, and carries on without it", async () => {
    const cookie = await signedIn(ROB, now() - 60);
    vi.mocked(refresh).mockRejectedValue(new Error("invalid_grant"));

    const read = await call(GET, "products/1", { cookie });
    expect(upstream().headers.get("authorization")).toBeNull();
    expect(read.status).toBe(200);
    expect(read.headers.get("set-cookie")).toMatch(/^session=;/);

    const write = await call(POST, "products/1/reviews", { method: "POST", cookie, body: "{}" });
    expect(write.status).toBe(401);
    expect(write.headers.get("set-cookie")).toMatch(/^session=;/);
  });

  it("relays storage's own refusals, and says when it cannot be reached", async () => {
    storage.mockResolvedValueOnce(
      Response.json({ status: 412, title: "Precondition Failed" }, {
        status: 412,
        headers: { "Content-Type": "application/problem+json" },
      }),
    );
    const stale = await call(PUT, "reviews/7", { method: "PUT", cookie: await signedIn(ROB), body: "{}" });
    expect(stale.status).toBe(412);
    expect(stale.headers.get("content-type")).toBe("application/problem+json");

    storage.mockRejectedValueOnce(new TypeError("fetch failed"));
    const down = await call(GET, "products");
    expect(down.status).toBe(502);
    expect(await down.json()).toMatchObject({ title: "Storage unavailable" });
  });
});
