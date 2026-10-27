import { describe, expect, it } from "vitest";

import { safeNext } from "./http";
import { seal, unseal } from "./sealed";

const SECRET = "a-session-secret-of-at-least-32-characters";

describe("sealed cookies", () => {
  it("give back what was sealed", async () => {
    const sealed = await seal({ access: "token", expiresAt: 1 }, SECRET, 60);
    expect(sealed.split(".")).toHaveLength(5);
    expect(sealed).not.toContain("token");
    expect(await unseal(sealed, SECRET)).toMatchObject({ access: "token", expiresAt: 1 });
  });

  it("refuse anything changed, sealed with another secret, expired or missing", async () => {
    const sealed = await seal({ access: "token" }, SECRET, 60);
    const [header, key, iv, ciphertext, tag] = sealed.split(".");
    const flipped = `${ciphertext[0] === "A" ? "B" : "A"}${ciphertext.slice(1)}`;
    expect(await unseal([header, key, iv, flipped, tag].join("."), SECRET)).toBeNull();
    expect(await unseal(sealed, `${SECRET}!`)).toBeNull();
    expect(await unseal(await seal({ access: "token" }, SECRET, -1), SECRET)).toBeNull();
    expect(await unseal(undefined, SECRET)).toBeNull();
    expect(await unseal("not sealed", SECRET)).toBeNull();
  });
});

describe("safeNext", () => {
  it("keeps paths of this application", () => {
    expect(safeNext("/products/1?x=y")).toBe("/products/1?x=y");
  });

  it("refuses other sites", () => {
    for (const next of ["https://evil.example/", "//evil.example/", "/\\evil.example", "evil", "", null, undefined]) {
      expect(safeNext(next)).toBe("/");
    }
  });
});
