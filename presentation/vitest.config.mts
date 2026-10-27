import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      // Next.js resolves this to a no-op on the server; tests run there too.
      "server-only": fileURLToPath(new URL("./src/test/server-only.ts", import.meta.url)),
    },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
    // Arbitrary test secrets: the stack's own values live in .env (git-ignored).
    env: {
      KEYCLOAK_CLIENT_SECRET: "test-client-secret",
      SESSION_SECRET: "a-test-session-secret-of-at-least-32-characters",
    },
    // In CI (.gitlab-ci.yml), results and coverage are also written in the
    // formats GitLab shows on a merge request.
    reporters: process.env.CI ? ["default", "junit"] : ["default"],
    outputFile: { junit: "test-results/vitest-junit.xml" },
    // npm run coverage: coverage/index.html
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.ts", "src/test/**"],
      reporter: process.env.CI ? ["text-summary", "html", "cobertura"] : ["text-summary", "html"],
    },
  },
});
