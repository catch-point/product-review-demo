import { describe, expect, it } from "vitest";

import { access } from "./access";

describe("access", () => {
  it("lets anyone read the storage service's documents", () => {
    for (const path of ["products", "products/1", "products/1/reviews", "reviews/2", "comments/3", "categories"]) {
      expect(access("GET", path, null)).toEqual({ allowed: true });
    }
    expect(access("GET", `images/${"a".repeat(64)}.png`, null)).toEqual({ allowed: true });
  });

  it("proxies nothing else", () => {
    for (const path of ["actuator/health", "openapi.yml", "reviews/2/comments", "products/1/../../admin", "images/x.png"]) {
      expect(access("GET", path, "owner")).toMatchObject({ allowed: false, status: 404 });
    }
    expect(access("POST", "people", "reviewer")).toMatchObject({ allowed: false, status: 404 });
    expect(access("DELETE", "people/1", "reviewer")).toMatchObject({ allowed: false, status: 404 });
    expect(access("PATCH", "products/1", "owner")).toMatchObject({ allowed: false, status: 404 });
  });

  it("needs a session to write", () => {
    expect(access("POST", "products/1/reviews", null)).toMatchObject({ allowed: false, status: 401 });
  });

  it("lets only owners manage products", () => {
    for (const [method, path] of [
      ["POST", "products"],
      ["PUT", "products/1"],
      ["DELETE", "products/1"],
    ]) {
      expect(access(method, path, "owner")).toEqual({ allowed: true });
      expect(access(method, path, "reviewer")).toMatchObject({ allowed: false, status: 403 });
    }
  });

  it("lets only reviewers review", () => {
    expect(access("POST", "products/1/reviews", "reviewer")).toEqual({ allowed: true });
    expect(access("POST", "products/1/reviews", "owner")).toMatchObject({ allowed: false, status: 403 });
    expect(access("PUT", "reviews/1", "owner")).toMatchObject({ allowed: false, status: 403 });
  });

  it("lets only owners reply to reviews", () => {
    for (const [method, path] of [
      ["POST", "reviews/1/comments"],
      ["PUT", "comments/1"],
      ["DELETE", "comments/1"],
    ]) {
      expect(access(method, path, "owner")).toEqual({ allowed: true });
      expect(access(method, path, "reviewer")).toMatchObject({ allowed: false, status: 403 });
    }
  });

  it("lets both upload images", () => {
    for (const role of ["reviewer", "owner"] as const) {
      expect(access("POST", "images", role)).toEqual({ allowed: true });
    }
  });
});
