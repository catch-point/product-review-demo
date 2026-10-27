import { describe, expect, it } from "vitest";

import { safePath } from "./safe-path";

describe("safePath", () => {
  it("keeps paths of this application", () => {
    expect(safePath("/products/1?x=y")).toBe("/products/1?x=y");
    expect(safePath("/my-products#new")).toBe("/my-products#new");
    expect(safePath("/")).toBe("/");
  });

  it("is null for anything else, so callers choose where to go instead", () => {
    for (const next of ["https://evil.example/", "//evil.example/", "/\\evil.example", "evil", "", null, undefined]) {
      expect(safePath(next)).toBeNull();
    }
  });

  it("is null for other sites hidden by characters the URL parser drops", () => {
    for (const next of ["/\t/evil.example", "/\n/evil.example", "/\r\n/evil.example", "/\t\\evil.example"]) {
      expect(safePath(next)).toBeNull();
    }
  });
});
