import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import { installPreload, withoutPreload } from "@/test/preload";

import { deploymentProblems } from "./deployment";

describe("deploymentProblems", () => {
  beforeAll(installPreload);
  afterEach(() => vi.unstubAllEnvs());

  it("is none with PUBLIC_URL set and the preload loaded", () => {
    vi.stubEnv("PUBLIC_URL", "http://localhost:3000");
    expect(deploymentProblems()).toEqual([]);
  });

  it("names a missing or unusable PUBLIC_URL", () => {
    vi.stubEnv("PUBLIC_URL", "");
    expect(deploymentProblems()).toEqual([expect.stringContaining("PUBLIC_URL is not set")]);
    vi.stubEnv("PUBLIC_URL", " ");
    expect(deploymentProblems()).toEqual([expect.stringContaining("PUBLIC_URL is not set")]);
    vi.stubEnv("PUBLIC_URL", "//localhost:3000");
    expect(deploymentProblems()).toEqual([expect.stringContaining("PUBLIC_URL is not a URL")]);
    vi.stubEnv("PUBLIC_URL", "ftp://localhost");
    expect(deploymentProblems()).toEqual([expect.stringContaining("PUBLIC_URL must be an http or https URL")]);
  });

  it("names a missing preload", () => {
    vi.stubEnv("PUBLIC_URL", "http://localhost:3000");
    expect(withoutPreload(deploymentProblems)).toEqual([expect.stringContaining("client-address.cjs is not loaded")]);
  });
});
