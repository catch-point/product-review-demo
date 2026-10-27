import Link from "next/link";

export const metadata = {
  title: "About & Highlights",
  description: "Portfolio demo highlights: architecture, concurrency, security, and testing.",
};

export default function AboutPage() {
  return (
    <div className="mx-auto max-w-4xl px-4 py-10 xl:px-0">
      <h1 className="mb-2 text-3xl font-bold text-gray-800">About &amp; Highlights</h1>
      <p className="mb-8 text-gray-600">
        This is a portfolio demo project showcasing full-stack design, security, and testing practices.
      </p>

      <section className="mb-8">
        <h2 className="mb-3 text-xl font-semibold text-gray-800">Architecture</h2>
        <ul className="list-disc space-y-2 pl-6 text-gray-700">
          <li>
            <strong>BFF Pattern:</strong> Next.js keeps tokens in HttpOnly cookies, proxies to the API, and enforces
            role-based routing.
          </li>
          <li>
            <strong>Multi-service Stack:</strong> Storage (Spring Boot), Identity (Keycloak), Presentation (Next.js),
            Edge (nginx) with fixed-address trust for client IPs.
          </li>
          <li>
            <strong>Role Separation:</strong> Database migrator vs app service roles with least-privilege access.
          </li>
        </ul>
      </section>

      <section className="mb-8">
        <h2 className="mb-3 text-xl font-semibold text-gray-800">Concurrency, Caching &amp; Audit</h2>
        <ul className="list-disc space-y-2 pl-6 text-gray-700">
          <li>
            <strong>Optimistic Concurrency:</strong> ETags with <code>If-Match</code>; returns 412 on conflicts, 428 if
            preconditions missing.
          </li>
          <li>
            <strong>Cache Invalidation:</strong> Caffeine in-memory cache + PostgreSQL <code>LISTEN/NOTIFY</code> journal
            events for cross-process consistency.
          </li>
          <li>
            <strong>Append-only Audit:</strong> Triggers record every change, with its actor and transaction, in a journal
            the service can neither skip nor change; it is not exposed via the API.
          </li>
          <li>
            <strong>Soft Delete:</strong> Discontinued products hidden from discovery while preserving reviews and replies.
          </li>
        </ul>
      </section>

      <section className="mb-8">
        <h2 className="mb-3 text-xl font-semibold text-gray-800">Security</h2>
        <ul className="list-disc space-y-2 pl-6 text-gray-700">
          <li>
            <strong>OIDC:</strong> Keycloak authorization code flow with PKCE, confidential client, email verification and
            brute-force protection.
          </li>
          <li>
            <strong>Authorization:</strong> Enforced by resource ownership and roles at the API; BFF prevents unauthorized
            actions.
          </li>
          <li>
            <strong>Rate Limiting:</strong> Per-IP and per-person limits at edge and application level.
          </li>
        </ul>
      </section>

      <section className="mb-8">
        <h2 className="mb-3 text-xl font-semibold text-gray-800">API &amp; Data</h2>
        <ul className="list-disc space-y-2 pl-6 text-gray-700">
          <li>
            <strong>JSON-LD/Hydra:</strong> Schema.org terms, Hydra collections with paging and search templates.
          </li>
          <li>
            <strong>Constraints:</strong> One review per product per reviewer, one reply per review by owner, image path
            normalization.
          </li>
          <li>
            <strong>Migrations:</strong> Flyway with role-based migration strategy.
          </li>
        </ul>
      </section>

      <section className="mb-8">
        <h2 className="mb-3 text-xl font-semibold text-gray-800">Testing</h2>
        <ul className="list-disc space-y-2 pl-6 text-gray-700">
          <li>
            <strong>Unit:</strong> Vitest for utilities and server logic.
          </li>
          <li>
            <strong>Integration:</strong> Spring Boot + Testcontainers for the storage API.
          </li>
          <li>
            <strong>E2E:</strong> Playwright journeys covering owner, reviewer, and admin flows.
          </li>
        </ul>
      </section>

      <div className="flex flex-wrap gap-4">
        <Link href="/" className="rounded bg-blue-600 px-4 py-2 text-white hover:bg-blue-700">
          Browse Products
        </Link>
        <a
          href="https://github.com/catch-point/product-review-demo"
          target="_blank"
          rel="noopener noreferrer"
          className="rounded border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
        >
          View Repository
        </a>
        <a
          href="https://github.com/catch-point/product-review-demo/blob/main/docs/DESIGN.md"
          target="_blank"
          rel="noopener noreferrer"
          className="rounded border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
        >
          Design Docs
        </a>
      </div>
    </div>
  );
}
