import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { contentSecurityPolicy, proxy } from "./proxy";

describe("content security policy", () => {
  it("allows this origin, a per-request nonce, and the icon font", () => {
    const policy = contentSecurityPolicy("abc", false);
    expect(policy).toContain("default-src 'self'");
    expect(policy).toContain("script-src 'self' 'nonce-abc' 'strict-dynamic'");
    expect(policy).not.toContain("unsafe-eval");
    expect(policy).not.toContain("unsafe-inline");
    // Whole directives: nothing but this origin for styles and fonts.
    expect(policy.split("; ")).toContain("style-src 'self' 'nonce-abc'");
    expect(policy).toContain("img-src 'self' blob: data:");
    expect(policy.split("; ")).toContain("font-src 'self'");
    expect(policy).not.toMatch(/https?:\/\//);
    expect(policy).toContain("connect-src 'self'");
    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("base-uri 'self'");
    expect(policy).toContain("form-action 'self'");
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).not.toContain("upgrade-insecure-requests");
  });

  it("relaxes scripts and styles in development", () => {
    const policy = contentSecurityPolicy("abc", true);
    expect(policy).toContain("'unsafe-eval'");
    expect(policy.split("; ")).toContain("style-src 'self' 'unsafe-inline'");
    expect(policy).not.toMatch(/https?:\/\//);
  });

  it("sends a fresh nonce on the document", () => {
    const first = proxy(new NextRequest("http://localhost:3000/"));
    const second = proxy(new NextRequest("http://localhost:3000/products/1"));
    const policy = first.headers.get("content-security-policy");
    const nonce = policy?.match(/'nonce-([^']+)'/)?.[1];
    expect(nonce).toBeTruthy();
    expect(second.headers.get("content-security-policy")).not.toContain(`'nonce-${nonce}'`);
  });
});
