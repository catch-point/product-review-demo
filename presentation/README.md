# Presentation: the product review web application

A Next.js application (React, Redux Toolkit, Tailwind CSS, TypeScript).

- **Reviewers** browse every product (best rated first, with infinite
  scroll and filters for category and rating range), open one for its
  details and reviews, and review it, once: after that the form is their
  review, to update or delete.
- **Product owners** see their own products and add, edit and discontinue
  them, and reply to their reviews, once each (only a product's owner
  replies): after that the reply is theirs to edit or delete. A discontinued
  product leaves listings and takes no new reviews; its reviews and replies
  stay readable through their own IRIs.

People register and sign in with their email and password, choosing their
role when they register, on the identity project's Keycloak. This
application keeps no accounts, passwords or other data of its own: no
database and no files.

## Run

From the repository root, `docker compose up -d --build` starts everything:
Keycloak on :8180, Mailpit on :8025, the storage service on :8080 and this
application, in a container, at http://localhost:3000. The application and
Keycloak are reached through the edge (`edge/`, nginx), which publishes both
ports. Registering sends a
verification email: in development, open it in Mailpit at
http://localhost:8025.

To work on it, stop its container and run the development server instead
(Node.js 22 or later). It must be on :3000, the address Keycloak sends people
back to, so the edge moves its port for the application aside (Keycloak stays
on :8180 behind it). The development server is reached directly, without the
edge's limits and caching:

```sh
scripts/stack.sh presentation stop     # or the "Presentation: Stop" task
PRESENTATION_HTTP_PORT=3100 scripts/stack.sh edge up
cd presentation
npm install
npm run dev        # http://localhost:3000
```

Stop the development server, then `scripts/stack.sh presentation up` builds
and starts the container again, with the edge back on :3000.

| Script | |
|---|---|
| `npm run dev` | Development server |
| `npm run build` / `npm start` | Production build and server (needs `PUBLIC_URL`, such as `PUBLIC_URL=http://localhost:3000 npm start`) |
| `npm test` | Unit tests (Vitest) |
| `npm run coverage` | Unit tests, with their coverage in `coverage/index.html` |
| `npm run e2e` | End-to-end tests (Playwright), against the running stack |
| `npm run lint` / `npm run typecheck` | ESLint, TypeScript |

If `npm run dev` keeps reloading the page after a production build, delete
`.next` and start it again: with Next.js 16.4.2, a development server started
over a production build's `.next` reloads every page in a loop.

Configuration is in `.env.example`; `scripts/secrets.sh` generates the
repository root's `.env` (git-ignored), symlinked here as `.env`, so
`npm run dev` and the Docker stack share the same secrets — copy
`.env.example` to `.env.local` only to override. In production,
`KEYCLOAK_CLIENT_SECRET` and `SESSION_SECRET` must be set.

### End-to-end tests

`e2e/` drives a browser through each kind of person's journey: a reviewer
browsing, reviewing and filtering; an owner managing products and signing
out and in; one review per product; discontinued products; page titles; token
renewal; and the admin role. They run against the whole stack, started as
above (the container or `npm run dev`) and seeded with the product catalog
(`storage/scripts/seed-products.sh`, as they browse past the 36th product),
and register new accounts on Keycloak, confirmed through
Mailpit, each time. `e2e/global-setup.ts` checks first that the application,
Keycloak and Mailpit answer and that 40 products or more are listed;
otherwise nothing runs, and it says what to start or seed.

They fail on any error in a page: a script error (such as a hydration
mismatch), or anything the page logs, each failed request among them. A test
that provokes a refusal on purpose declares it, by status and path, in the
step that does, and it must then happen:

```ts
allowErrors({ status: 409, url: /^\/api\/storage\/products\/\d+\/reviews$/ });
```

Chrome's `ERR_NETWORK_CHANGED` (the host's network changing, as Docker's does)
is the one error ignored everywhere.

```sh
npx playwright install chromium     # once; or E2E_CHANNEL=chrome to use Google Chrome
npm run e2e                         # the report: npx playwright show-report
```

`E2E_BASE_URL`, `E2E_KEYCLOAK_URL`, `E2E_MAILPIT_URL` and `SESSION_SECRET`
change the defaults (http://localhost:3000, :8180 and :8025);
`SESSION_SECRET` otherwise comes from the repository root's `.env`, with
which the token tests read and forge session cookies.

### Docker

`Dockerfile` builds Next.js's self-contained server (`output: "standalone"`,
only when `NEXT_OUTPUT=standalone`, so `npm start` still works) and runs it
as `node`, without the development dependencies. `compose.yaml` runs it
against the other containers:

- `STORAGE_URL` is `http://storage:8080`, on the Compose network.
- `KEYCLOAK_ISSUER` stays `http://localhost:8180/realms/reviews`, the address
  browsers sign in at and the tokens' issuer, while `KEYCLOAK_INTERNAL_URL`
  (`http://keycloak:8080`) is where this server fetches Keycloak's metadata
  and calls its token, key and sign-out endpoints (`src/server/oidc.ts`).
- `PUBLIC_URL` is `PRESENTATION_URL` (`http://localhost:3000`), the address
  browsers reach it at through the edge. It names itself by that origin, in
  sign-in redirects and in the `X-Forwarded-Host` and `-Proto` it sends the
  storage service, never by the `Host` a request claims: the edge caches
  anonymous reads for everyone, so a response with links built from another
  host must never be made. In production the server won't start without it
  (`src/instrumentation.ts`).
- It is a production build using the secrets from the repository root's
  `.env` (`scripts/secrets.sh`); without them Compose refuses to start it.
  `ALLOW_DEV_SECRETS=true` would instead let it run without
  `KEYCLOAK_CLIENT_SECRET` — a development deployment only.
- It is not published: browsers reach it through the edge (`edge/`, nginx)
  on :3000, which limits each address and caches images, build files and
  anonymous reads. It is on the `backchannel` network only, at the fixed
  address `PRESENTATION_ADDRESS` (`172.31.250.10`), the one address from
  which the storage service and Keycloak believe `X-Forwarded-For`; it
  believes the edge's (`TRUSTED_PROXIES`, `172.31.250.11`).

## How it works

```
browser ──► Keycloak (:8180)            sign-in and registration pages
   │
   └──────► Next.js ──► /api/auth/*      OpenID Connect client, session cookie ──► Keycloak
                    └─► /api/storage/*   proxy ──► storage service (:8080)
```

- **A single-page application.** Every page is a client component; links and
  redirects use the App Router, which navigates with `history.pushState` (or
  `replaceState`), so Back and Forward move between the application's pages.
  Next.js keeps the page the user left mounted but hidden, so Back returns to
  a list as it was, scrolled where it was. Filters are in the URL
  (`/?category=Audio&minRating=4`) and replace the history entry rather than
  adding one. Saving a form also replaces it, so Back skips it.
- **Data.** Redux Toolkit holds RTK Query's cache (`src/store`) and whether
  the user just signed out. The listings are RTK Query infinite queries: each
  page's Hydra `view.next` link is the next page's parameter, and an
  `IntersectionObserver` fetches it as the end of the list nears.
- **Accounts** are Keycloak's (realm `reviews`, in
  `../identity/realm/reviews-realm.json`): passwords, email
  verification, password reset and brute-force protection, and the role
  people choose when they register, which they cannot change afterwards. Its
   sign-in and registration pages are a theme
   (`../identity/themes/reviews`).
- **Signing in** (`src/server/oidc.ts`) is the OpenID Connect authorization
  code flow with PKCE, as a confidential client. `/login` and `/register`
  hand over to Keycloak with `location.replace`, so Back from Keycloak's page
  returns to the page before. Keycloak returns to `/api/auth/callback`, which
  checks the state and nonce, exchanges the code, registers the person with
  the storage service the first time (`POST /people`), and goes back to the
  page that asked them to sign in.
- **The session** is the user's tokens in an httpOnly, SameSite=Lax cookie,
  encrypted with `SESSION_SECRET` (`src/server/session.ts`), so nothing is
  stored here. The access token is the bearer token for the storage service;
  its claims give the user's email, name and role, checked against
  Keycloak's keys. It is renewed with the refresh token shortly before it
  expires. Signing out ends the Keycloak session too, from the server.
- **The proxy** (`src/app/api/storage/[...path]/route.ts`) forwards only the
  storage operations the application uses, adds the session's bearer token,
  and enforces the roles (`src/server/access.ts`): only owners write
  products, and only reviewers write reviews (the role comes from the
  access token, which Keycloak signs). The storage service enforces the
  same roles itself, decides from its rows who owns or wrote what, and
  discontinues a product on its owner's DELETE. It refuses, with 413,
  request bodies larger than storage accepts: 64 KB, or 5 MB (plus its
  encoding) for an image upload. A body with a larger `Content-Length` is
  refused unread; one without (chunked) as soon as reading passes the limit,
  so it never holds more. It relays storage's 429 (rate limited) with its
  `Retry-After`. The proxy passes
  `X-Forwarded-Host`, `-Proto` and `-Prefix`, so the IRIs in the storage
  service's responses are this application's `/api/storage` URLs. The host
  and protocol are `PUBLIC_URL`'s when it is set; otherwise the request's,
  with a proxy's `X-Forwarded-Host` and `-Proto` believed only from
  `TRUSTED_PROXIES` (`client-address.cjs` drops them from anyone else). It also
  passes ETags and conditional headers through, so edits and deletes send
  `If-Match`, and a conflicting change is reported, not overwritten. It
  passes storage's `Cache-Control` too, so a shared cache in front of this
  application may keep what is the same for everyone, except that any
  response setting or clearing a cookie (renewing the session, say) is
  `private, no-store` (`src/server/http.ts`).
- **Pages** carry a content security policy (`src/proxy.ts`). Each document
  gets a new nonce, and Next.js stamps it on its scripts. Scripts and
  connections stay on this origin. Images are this origin, a `blob:` preview,
  or a `data:` URL. Fonts are this origin's too, so a page loads nothing
  from another host: Noto Sans through `next/font` (downloaded at build
  time), and the star icons from a two-icon subset of Material Symbols in
  `src/app/fonts/` (`layout.tsx` says how to add an icon). API responses are
  outside that policy, so an uploaded image keeps the sandbox header storage
  sends.
- **Client addresses.** `client-address.cjs`, preloaded with
  `node --require` (the `dev` and `start` scripts, and the Docker image),
  rewrites each request's `X-Forwarded-For` before Next.js reads it. The
  header becomes the address the client connected from. A reverse proxy in
  `TRUSTED_PROXIES` (addresses or CIDR ranges; none by default, the edge in
  Compose) may name the client instead: then it is the last address before
  the trusted proxies. Started without the preload, the server believes no
  forwarding header and passes on no client address; in production it won't
  start at all (`src/instrumentation.ts`, `src/server/deployment.ts`).
  Next.js would otherwise keep any `X-Forwarded-For` the client sent. That
  address is sent on, as `X-Forwarded-For`, to the storage service (the
  proxy, the callback's registration, the product pages' titles), which
  counts anonymous requests against it. It also goes to Keycloak with the
  code exchange, token renewal and sign-out (`src/server/oidc.ts`), so its
  events and sessions show the person's address, not this server's.
  Through Docker's published port every browser arrives at the edge from
  the network's gateway, so locally they share one address; in production
  the edge would see each visitor's own.
- **Owners' products** are the `owns` of their Person (`GET /people/me`),
  each read on its own as the list scrolls, so edits and discontinuations
  have its ETag.

## Not done

- Registration and sign-in leave the single-page application for Keycloak's
  pages and come back with a full page load.
- Uploaded images are stored by the storage service as paths,
  `/images/<sha256>.<ext>`, and each response names them as URLs on the
  address it was asked at, so a different host name for this application
  needs nothing rewritten.
