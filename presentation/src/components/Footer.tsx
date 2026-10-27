import Link from "next/link";

export function Footer() {
  return (
    <footer className="mt-10 flex bg-gray-800 py-6">
      <div className="mx-auto w-full max-w-6xl px-4 xl:px-0">
        <div className="mb-2 text-sm font-semibold text-white">Product Reviews • Portfolio Demo</div>
        <div className="mb-4 text-xs text-gray-300">
          Showcasing full-stack architecture: JSON-LD/Hydra API, optimistic concurrency, audit journal + NOTIFY, Keycloak
          OIDC, and comprehensive testing.
        </div>
        <div className="flex flex-wrap items-center gap-4 text-sm text-gray-300">
          <Link href="/about" className="hover:text-white">
            About &amp; Highlights
          </Link>
          <a
            href="https://github.com/catch-point/product-review-demo"
            target="_blank"
            rel="noopener noreferrer"
            className="hover:text-white"
          >
            Repository
          </a>
          <span className="text-gray-400">Built with Next.js, Spring Boot, Keycloak &amp; PostgreSQL</span>
        </div>
      </div>
    </footer>
  );
}
