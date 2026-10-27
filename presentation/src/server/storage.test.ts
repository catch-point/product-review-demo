import { afterEach, describe, expect, it, vi } from "vitest";

import { readProduct } from "./storage";

describe("readProduct", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads the product from storage, without a token", async () => {
    const fetch = vi.fn(async () => Response.json({ id: "x", type: "Product", name: "Bistro" }));
    vi.stubGlobal("fetch", fetch);
    expect((await readProduct("42"))?.name).toBe("Bistro");
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toMatch(/\/products\/42$/);
    expect(new Headers(init.headers).has("authorization")).toBe(false);
  });

  it("is null when there is no such product, storage fails or cannot be reached", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 404 })));
    expect(await readProduct("42")).toBeNull();
    vi.stubGlobal("fetch", vi.fn(async () => new Response("oops", { status: 500 })));
    expect(await readProduct("42")).toBeNull();
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("fetch failed"))));
    expect(await readProduct("42")).toBeNull();
  });

  it("asks storage only for product ids", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    expect(await readProduct("../people/me")).toBeNull();
    expect(fetch).not.toHaveBeenCalled();
  });
});
