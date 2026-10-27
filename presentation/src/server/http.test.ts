import { NextRequest, NextResponse } from "next/server";
import { describe, expect, it } from "vitest";

import { clearCookie, setCookie } from "./http";
import { relay } from "./storage";

describe("responses that set cookies", () => {
  const request = new NextRequest("http://localhost:3000/api/storage/products/1");

  it("are never cached, even when relaying a shared-cacheable resource", () => {
    const response = new NextResponse("{}", { headers: { "Cache-Control": "no-cache", ETag: 'W/"1"' } });
    setCookie(response, request, "session", "sealed", { maxAge: 60 });
    expect(response.headers.get("cache-control")).toBe("private, no-store");
    expect(response.headers.get("set-cookie")).toContain("session=sealed");
  });

  it("are never cached when clearing one either", () => {
    const response = new NextResponse("{}", { headers: { "Cache-Control": "no-cache" } });
    clearCookie(response, "session");
    expect(response.headers.get("cache-control")).toBe("private, no-store");
  });

  it("are the only ones: a relayed response keeps storage's caching", () => {
    const shared = relay(new Response("{}", { headers: { "Cache-Control": "no-cache", ETag: 'W/"1"' } }));
    expect(shared.headers.get("cache-control")).toBe("no-cache");
    expect(shared.headers.get("etag")).toBe('W/"1"');
    const mine = relay(new Response("{}", { headers: { "Cache-Control": "no-cache, private", Vary: "Authorization" } }));
    expect(mine.headers.get("cache-control")).toBe("no-cache, private");
    expect(mine.headers.get("vary")).toBe("Cookie");
  });
});
