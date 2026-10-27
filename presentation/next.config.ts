import type { NextConfig } from "next";

/**
 * Sent with every response (pages, the storage proxy's, static files), next
 * to proxy.ts's Content-Security-Policy on pages: no Referer beyond this
 * origin, no browser features the app does not use, and no guessing a
 * response's type from its content.
 */
const securityHeaders = [
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=(), payment=(), usb=(), browsing-topics=()",
  },
  { key: "X-Content-Type-Options", value: "nosniff" },
];

const nextConfig: NextConfig = {
  // The Docker image runs the self-contained server (.next/standalone); a
  // local `npm start` keeps using next start.
  output: process.env.NEXT_OUTPUT === "standalone" ? "standalone" : undefined,
  // No X-Powered-By: Next.js.
  poweredByHeader: false,
  headers: async () => [{ source: "/:path*", headers: securityHeaders }],
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
