# Product Reviews • Portfolio Demo

> **Portfolio Demo Project** — Built to showcase full-stack design, security, data modeling, and testing practices. Reviewers rate and review products; owners add their products and reply to the reviews they get.

A product review site. Reviewers rate and review products; owners add
their products and reply to the reviews they get. It is built as four small
projects, each with its own README:

| Project | What it is | Built with |
|---|---|---|
| [`presentation/`](presentation/README.md) | The web application: a single-page app and its backend-for-frontend | Next.js 16, React, Redux Toolkit / RTK Query, Tailwind |
| [`storage/`](storage/README.md) | The data: a JSON-LD REST API over PostgreSQL | Spring Boot 4, Java 25, Flyway, Caffeine, Bucket4j, MinIO (S3) |
| [`identity/`](identity/README.md) | Accounts, sign-in, registration and roles | Keycloak 26, with Mailpit to catch its email |
| [`edge/`](edge/README.md) | The way in: per-IP limits and caching | nginx 1.28 |

## Requirements

| To | You need |
|---|---|
| Run the application | Docker Engine with Docker Compose v2.20 or later (for `include`), Bash, and `openssl` (to generate the secrets) |
| Use it | A browser. Everything is on `localhost` |
| Seed sample data | `curl`, `jq` and `python3` |
| Work on `presentation/`, run its tests | Node.js 22 or later (`npm`) |
| Work on `storage/`, run its tests | JDK 25, named by `JAVA_HOME` or found on `PATH` (`./mvnw` fetches Maven). Its integration tests need Docker |
| Run the end-to-end tests | Node.js 22, and Playwright's Chromium (`npx playwright install chromium`) or Google Chrome |

## What This Demonstrates

- **API Design**: JSON-LD with schema.org terms and Hydra collections (paging, filters, search templates).
- **Concurrency & Consistency**: Optimistic concurrency with ETags/`If-Match`, row-level locking on writes, and 428/412 responses.
- **Caching**: In-memory Caffeine caches with drop-on-write and PostgreSQL `LISTEN/NOTIFY` journal events for cross-process invalidation.
- **Auditability**: Append-only audit journal recording every change with actor/txid and triggers; journal skipping restricted to migration owner.
- **Security**: Keycloak OIDC (PKCE, confidential client BFF), role-based access, email verification, brute-force protection, trusted proxy model with fixed addresses.
- **Data Modeling**: Soft-delete for products with review/reply retention, one-review-per-product and one-reply-per-owner-per-review, image path normalization and constraints.
- **Ops & Infra**: Multi-service Compose stack, per-service role separation, Flyway migrations, MinIO S3 storage, nginx edge caching/rate-limiting.
- **Testing**: Unit tests (Vitest), integration tests (Spring Boot/Testcontainers), and E2E journeys (Playwright) across owner/reviewer/admin flows.

The published ports bind to `127.0.0.1` by default (`BIND_ADDRESS`; the
storage database's port is always `127.0.0.1`).

## Get to the sign-in and registration pages

1. **Start the stack** from the repository root:

   ```sh
   scripts/stack.sh all up
   ```

   `scripts/stack.sh` is the supported way to run the stack. It generates the
   secrets (`scripts/secrets.sh`) if they are missing, waits until every
   service is healthy, then runs Keycloak's one-shot configuration container,
   which plain `docker compose up --wait` would count as a failure. Without
   the wrapper: `scripts/secrets.sh`, then `docker compose up -d --build`.

2. **Open http://localhost:3000** and choose **Register** (or **Sign in**)
   in the top bar. The application hands over to Keycloak's pages at
   http://localhost:8180, styled like the rest of the site.

3. **Register:** choose **Review products** (a reviewer) or **Manage my
   products** (an owner), then enter a name, an email (any address:
   nothing leaves your machine) and a password: at least 8 characters, and
   not your email. The role is fixed once chosen.

4. **Verify the email** in Mailpit (below). The link signs you in and
   returns you to the application: owners land on **My Products**,
   reviewers on the product listing.

To have something to browse, seed the sample product catalog:
`storage/scripts/seed-products.sh` (240 products, people and reviews). It
signs in with development tokens, which the storage service accepts only once
you opt in: add `DEV_TOKENS=true` to `.env`, then `scripts/stack.sh storage up`
(remove it, and run that again, to turn them off).

## Check Mailpit

Keycloak sends its email (verification, password reset) to
[Mailpit](https://mailpit.axllent.org), which keeps it instead of delivering
it:

- **In a browser:** open http://localhost:8025. The newest message is at the
  top ("Verify email"); open it and follow its verification link. Password
  reset emails arrive the same way.
- **From a script:** Mailpit has an API, as the end-to-end tests use it:

  ```sh
  curl -s 'http://localhost:8025/api/v1/search?query=to:you@example.com' | jq '.messages[0].ID'
  curl -s http://localhost:8025/api/v1/message/<ID> | jq -r .Text
  ```

## Architecture

```
                 browser
                    │
        :3000 the application      :8180 sign-in, registration
                    │                    │
  ┌─────────────────▼────────────────────▼─────────────────┐
  │ edge (nginx): per-IP limits, caching, body and time    │
  │ limits, the client's address in X-Forwarded-For        │
  └─────────────────┬────────────────────┬─────────────────┘
                    │                    │
                    ▼                    ▼
  ┌───────────────────────┐  code    ┌──────────────────────┐
  │ presentation          │ exchange,│ keycloak             │
  │ Next.js SPA and its   │ renewal, │ accounts, roles,     │
  │ backend-for-frontend  │ sign-out │ email, tokens        │
  │ (keeps no data)       ├─────────►│                      │
  └───────────┬───────────┘          └──┬─────────┬──────┬──┘
              │ /api/storage/*          │         │      │
              │ + bearer token          │ signing │      ▼
              ▼                         │ keys    │  ┌──────────────┐
  ┌───────────────────────┐             │         │  │ mailpit      │
  │ storage               │◄────────────┘         │  │ :8025        │
  │ Spring Boot, JSON-LD  │                       │  └──────────────┘
  │ :8080                 │◄── storage-admin      ▼
  └───────────┬───────────┘    :8095       ┌──────────────┐
              ├─────────────────────────┐  │ keycloak-db  │
              ▼                         ▼  └──────────────┘
  ┌───────────────────────┐  ┌──────────────────────┐
  │ storage-db  :5433     │  │ storage-minio  :9100 │
  │ the review graph      │  │ the images' bytes    │
  └───────────────────────┘  └──────────────────────┘
```

Four containers run once and exit. Before the storage service,
`storage-db-roles` makes the schema's owner and `storage-migrate` migrates
the database as that owner and sets up the role the service connects as,
and `storage-minio-init` makes the images' bucket and the service's MinIO
user.
After Keycloak starts, `keycloak-config` applies what the realm import can't
set.

- **The edge** (nginx) is the only way in to the application and Keycloak.
  It limits each client address and caches what doesn't change: uploaded
  images and build files for everyone, and anonymous API reads for 2
  seconds.
- **The presentation server** is a backend-for-frontend:
  - The browser never holds a token. The server signs people in with
    Keycloak (OpenID Connect authorization code flow with PKCE, as a
    confidential client), calling Keycloak directly, not through the edge.
  - It keeps their tokens in an encrypted, `HttpOnly`, `SameSite=Lax` session
    cookie, and renews them before they expire.
  - Its `/api/storage/*` route proxies the storage API, adding the bearer
    token and refusing what the person's role can never do.
  - It keeps no data of its own: no database, no persistent storage.
- **The storage service** owns all the data. Its JSON-LD documents use
  schema.org terms (Product, Review, Rating, Comment, Person). Its
  collections are Hydra collections with paging links.
  - **Authorization:** it decides every write from the token's role and from
    who owns or wrote the row.
  - **Concurrency:** writes are optimistic. They must send the ETag they read
    as `If-Match`; otherwise they get 412 if stale, or 428 if missing.
  - **Audit:** a journal records every row change and who made it.
  - **Caching:** rows are cached in memory. The service drops its own changes
    from the cache as it makes them, and the journal's PostgreSQL `NOTIFY`
    tells it of everyone else's, so even changes made with `psql` reach the
    cache.
  - **Schema:** Flyway migrations from `V1` (`db/schema.sql`) onward, run by the
    one-shot `storage-migrate` container as the schema's owner. The service
    connects as a role of its own that only reads and writes rows. Collapsed migrations are documented in `docs/adr/`.
- **Keycloak** owns accounts and credentials:
  - registration with a role (reviewer or owner) that people can't change
    afterwards;
  - email verification and password reset;
  - brute-force lockout;
  - an `admin` realm role, kept for future use.
- **Each project runs alone** too: its own `compose.yaml`, which the root
  `compose.yaml` includes. `scripts/stack.sh` starts, stops, backs up,
  restores and resets them one project at a time.

### Docker containers

| Container | Image | Published on | Role |
|---|---|---|---|
| `edge` | `nginx:1.28-alpine` | :3000, :8180 | Per-IP limits, caching, the way in |
| `presentation` | built from `presentation/Dockerfile` (`node:22-alpine`) | (through the edge) | The web application |
| `storage` | built from `storage/Dockerfile` (`eclipse-temurin:25-jre`) | :8080 (API) | The JSON-LD API; actuator on :8081 inside Docker |
| `storage-db-roles` | `postgres:18-alpine` | | Runs once, before `storage-migrate`: as the superuser, makes the schema's owner (`storage/db/owner.sql`) |
| `storage-migrate` | the `storage` image | | Runs once, before `storage`: migrates the schema as its owner and sets up the service's role |
| `storage-db` | `postgres:18-alpine` | :5433 (always `127.0.0.1`) | The review graph |
| `storage-minio` | `cgr.dev/chainguard/minio`, pinned by digest | :9100 (always `127.0.0.1`) | The images' bytes, in the `review-images` bucket (MinIO's S3 API) |
| `storage-minio-init` | the `storage-minio` image | | Runs once, before `storage`: makes the bucket and the service's MinIO user (`storage/minio/init.sh`) |
| `storage-admin` | built from `storage/admin/Dockerfile` | :8095 | Spring Boot Admin: health, metrics, caches, logs of `storage` |
| `keycloak` | `quay.io/keycloak/keycloak:26.6.4` | (through the edge) | Accounts and sign-in |
| `keycloak-config` | `quay.io/keycloak/keycloak:26.6.4` | | Runs once: what the realm import can't set |
| `keycloak-db` | `postgres:18-alpine` | | Keycloak's data |
| `mailpit` | `axllent/mailpit:v1.31.6` | :8025 | Catches Keycloak's email |

The admin UIs are http://localhost:8180/admin (Keycloak: `admin` and
`KEYCLOAK_ADMIN_PASSWORD` from `.env`) and http://localhost:8095 (Spring Boot
Admin: `admin` and `ADMIN_PASSWORD`). Data lives in the volumes `keycloak-db`,
`storage-db` and `storage-minio`.

MinIO no longer publishes images of its community edition (Docker Hub and
quay.io refuse them), so the stack runs Chainguard's build of it from
MinIO's source. Chainguard's free images are its latest build only, so
`storage/compose.yaml` pins one by digest; to update, pull
`cgr.dev/chainguard/minio:latest` and put its digest there and in
`TestcontainersConfiguration.MINIO_IMAGE`. MinIO is AGPL-licensed. The
service speaks only the S3 API, so another S3-compatible store (AWS S3,
Garage, SeaweedFS) would serve as well.

### Two networks

The `default` network carries ordinary service-to-service traffic: the
databases, MinIO, Mailpit and Spring Boot Admin are on it with the services
that use them. The `backchannel` network (`172.31.250.0/28`) gives two containers
**fixed addresses**: the presentation server (`172.31.250.10`, on this
network alone) and the edge (`172.31.250.11`). Those fixed addresses are the
whole basis for trusting `X-Forwarded-For`: the edge names the browser's
address, the presentation server names it onward, and both are believed
because they come from those two addresses and no others.

### What happens to a request

1. The browser reaches the **edge**, which limits each client address (reads,
   writes, sign-in forms, open connections), bounds body size and time, serves
   what never changes (images, build files) from its cache, and names the
   client in `X-Forwarded-For`.
2. For the application, the edge passes the request to the **presentation
   server**, which proxies `/api/storage/*` to the storage service with the
   session's bearer token.
3. The **storage service** verifies that token against Keycloak's signing
   keys (cached for five minutes, and fetched again sooner when a token
   names a key not in the cache, as after Keycloak rotates its keys), checks the issuer, the audience and the
   role claim, and decides what the caller may do from the role and from who
   owns or wrote each row.
4. Rows come from **PostgreSQL**, behind the row cache.
5. Writes are optimistic: the row is locked and its `ETag` checked against
   `If-Match` in the same transaction as the change, and a stale `ETag` fails
   with 412 rather than overwriting someone else's work.
6. The response carries `ETag`, `Last-Modified` and `Cache-Control`. The edge
   keeps a successful anonymous read for 2 seconds, keyed by its URL and
   `Accept`; signed-in requests always go through.

Signing in goes the other way: the browser goes through the edge's `:8180` to
Keycloak, and Keycloak sends it back to the application with a code, which the
presentation server exchanges for the tokens.

## Why this approach

| Decision | Instead of | Why |
|---|---|---|
| **Keycloak** for accounts | Writing sign-up, passwords and email verification ourselves | Password hashing, lockout, email verification, reset flows and OIDC are already done and audited. The application never sees a password |
| **Backend-for-frontend**: tokens in an encrypted `HttpOnly` cookie | A browser SPA holding tokens in `localStorage` | A script injected into the page (XSS) cannot read or exfiltrate the tokens. Renewal happens on the server. The browser talks to one origin, so no CORS |
| **A separate storage service** owning the data | Next.js talking to the database directly | The split means authorization lives in one place, next to the data. Any client gets the same rules, with the web app as just one consumer |
| **JSON-LD with schema.org and Hydra** | Ad hoc JSON | The documents mean something outside this app: a JSON-LD processor expands them to schema.org terms (the integration tests check this). Paging links are in the response, so clients follow `next` instead of building URLs |
| **Authorization in storage too**, not only in the proxy | Trusting the web app's checks | Defence in depth. The proxy refuses what a role can never do, early. Storage decides everything, from the token and the rows, with them locked, so no client can skip a check |
| **Optimistic concurrency** (ETag / `If-Match`) | Last write wins | Two tabs or two people can't silently overwrite each other. A conflict is shown, with the newer version |
| **One review per reviewer, one reply per owner**, enforced by unique indexes | Checks in code alone | The database guarantees it even under races. Code turns the violation into a helpful 409 |
| **Caches dropped by the journal's `NOTIFY`** | Time-to-live alone, or no cache | Reads are served from memory, yet a change (from this instance, another one, or `psql`) is seen within about a second |
| **An nginx edge** in front | Limits in the applications alone | It limits each connection's address (see the edge's caveat on shared addresses), absorbs floods before they cost the application anything, and caches the immutable parts. The applications keep their own per-person limits behind it |

## Security considerations

- **Secrets** are generated per checkout by `scripts/secrets.sh` into `.env`
  (git-ignored, mode 600), and nothing has a default password. The
  presentation container refuses to start in production mode without them.
- **Signing in:**
  - The authorization code flow with PKCE, with the application as a
    confidential client: the client secret stays on the server and never
    reaches the browser. Direct access grants and the implicit flow are off.
  - Registration requires a verified email. Passwords must be at least 8
    characters and not the username or email. An account locks after 10
    failed attempts.
- **Sessions:**
  - **Cookie:** encrypted, `HttpOnly`, `SameSite=Lax`, and `Secure` behind
    HTTPS.
  - **Cross-site requests:** writes from other origins are refused (`Origin`
    check).
  - **Lifetimes:** access tokens last 5 minutes and are renewed with the
    refresh token shortly before they expire; sessions last at most 10 hours
    (8 idle).
  - **Signing out** ends the Keycloak session too, from the server.
- **Tokens:** the storage API checks the issuer, the audience (`storage`) and
  the signature against Keycloak's keys, so a token from elsewhere is refused.
  Development HS256 tokens (`storage/scripts/dev-token.sh`, used by the seed
  scripts) are off unless `DEV_TOKENS=true` is in `.env`: they are checked
  for nothing but their signature (`JWT_SECRET`) and expiry, so anyone with
  `.env` could sign one for anyone.
- **Authorization:**
  - **The role** comes from the signed token, never from the request. It is
    chosen at registration and cannot be changed afterwards. The `admin` role
    cannot be given to oneself: `scripts/stack.sh identity admin` grants it.
  - **Two checks on every write.** Presentation's allow-list uses the role in
    the access token. Storage uses that role and the owner or author stored
    on the row, after the row lock and before `If-Match` is evaluated, so a
    stranger learns nothing from a precondition failure and an owner cannot
    slip a change in between.
  - Writes need `If-Match` (428 without it, 412 when the row changed).
    Deleting a product discontinues it: it leaves the listings, and its
    reviews and replies stay ([ADR 0003](docs/adr/0003-soft-delete-products-with-review-retention.md)).
  - The API never shows a person's email address to anyone else.
- **Input:**
  - **Body size:** at most 64 KB of JSON, or 5 MB (plus its encoding) for an
    image, at the proxy and again in storage; the edge refuses anything over
    6 MB. A body with a larger `Content-Length` is refused unread, a chunked
    one as soon as reading passes the limit.
  - **Fields:** every field has a maximum length.
  - **JSON parsing:** Jackson refuses long strings and deep nesting.
  - **Uploads:** checked by their content, and must decode as JPEG, PNG,
    GIF or WebP within a pixel limit. SVG is refused. Images are stored
    under their SHA-256. A product's or review's `image` must be one of
    these uploads: it is stored as its path, `/images/<sha256>.<ext>`, and
    served as a URL on the address the request came to.
- **Abuse limits, in layers:**
  - **The edge, per IP:** reads, writes, and sign-in form posts, the last
    most strictly. The IP is the connection's, so browsers that reach the
    edge through one address share its allowance: all of this machine's do
    (Docker's port forwarding hands them over from `172.31.250.1`), as
    would everyone behind a proxy in front of the edge
    ([edge/README.md](edge/README.md)).
  - **Storage, per person (or per IP when anonymous):** 600 reads, 60
    writes and 10 uploads a minute.
  - **Keycloak, per account:** a lockout after 10 failed passwords.

  Past a limit, the answer is 429 with `Retry-After`.
- **Client addresses** can't be spoofed:
  - **Who is trusted:** only the edge (to presentation and Keycloak) and
    presentation (to storage), at fixed addresses on the `backchannel`
    network, may name a client in `X-Forwarded-For`.
  - **Everyone else:** a header from anyone else is replaced, by the edge
    and by the presentation server before Next.js reads it, or ignored, by
    storage and Keycloak.
  - **The application's own address** is configured (`PUBLIC_URL`, from
    `PRESENTATION_URL`), never taken from the request: the edge drops a
    client's `X-Forwarded-Host`, `-Port`, `-Prefix` and `Forwarded`, and the
    presentation server ignores them from anyone but the edge. Otherwise a
    request claiming another host could have the edge cache links to it for
    everyone. So use the application at that address alone: at another,
    such as `http://127.0.0.1:3000`, sign-in comes back to `PUBLIC_URL` and
    changes are refused (403), as they come from a foreign origin.
  - **Both fail closed:** in production the presentation server won't start
    without `PUBLIC_URL`, or without `client-address.cjs` preloaded
    (`presentation/src/instrumentation.ts`); in development each is a
    warning. Without the preload it believes no forwarding header and passes
    on no client address.
- **Response headers:** pages carry a nonce-based `Content-Security-Policy`
  (`presentation/src/proxy.ts`), and every response from the application
  `Referrer-Policy: strict-origin-when-cross-origin`, a `Permissions-Policy`
  turning off the camera, microphone, location, payment and USB, and
  `X-Content-Type-Options: nosniff`, with no `X-Powered-By`
  (`presentation/next.config.ts`).
- **Bounded work:**
  - paging stops at offset 10000;
  - image decoding is limited to one at a time per processor (at least 2);
  - database waits and statements time out, answering 503 with
    `Retry-After`;
  - slow clients are cut off: after 10 seconds at the edge, 20 in storage.
- **Database roles:** separate databases, separate credentials, and three
  roles in the storage database, each used by one container:
  - **`postgres`** (`STORAGE_DB_PASSWORD`) is the superuser. Besides the
    database container, only the one-shot `storage-db-roles` has its
    password, to make the schema's owner (`storage/db/owner.sql`). Backups
    and restores run as it, inside the database container.
  - **`reviews_owner`** (`STORAGE_DB_OWNER_PASSWORD`) owns the database, the
    schema and the journal, and is not a superuser. Only the one-shot
    `storage-migrate` container has its password: it runs the migrations,
    then sets up the service's role. Its one extra attribute, `CREATEROLE`,
    reaches only the roles it manages, and cannot grant attributes it
    lacks, such as `SUPERUSER`.
  - **`reviews_service`** (`STORAGE_DB_SERVICE_PASSWORD`) is the service's.
    It reads and writes rows and nothing more: it can't create, alter or drop
    anything, disable a trigger, write or skip the journal, or read Flyway's
    history, and isn't a superuser. `storage-migrate` creates it, grants it
    afresh and sets its password on every run. Likewise `storage-db-roles`
    sets the owner's, so a new owner or service password in `.env` takes
    effect at the next `scripts/stack.sh storage up`. The service refuses to
    start connected as anything more (the owner, a superuser), and
    migrating refuses to make the owner the service's role.
- **Image storage:** the images' bytes are objects in a private MinIO
  bucket, served only through the storage service, never by MinIO itself.
  - **The root user** (`MINIO_ROOT_PASSWORD`) is for the one-shot
    `storage-minio-init` (`storage/minio/init.sh`), backups, restores and
    pruning.
  - **`reviews-images`** (`MINIO_IMAGES_SECRET_KEY`) is the service's: it
    may read, write and list that bucket and nothing more (no deleting, no
    other bucket, no administration). `storage-minio-init` sets its secret
    on every run, so a new one in `.env` takes effect at the next
    `scripts/stack.sh storage up`.
  - **Writes never replace an image's bytes:** each is named by its
    SHA-256 and written with `If-None-Match: *`. An identical upload copies
    the image onto itself instead, which marks it uploaded again.
  - **Uploads lose their metadata** before they are stored: EXIF (GPS
    location, camera, time), XMP, IPTC, comments, text chunks and anything
    after the image's end. Colour profiles, transparency, animation and a
    JPEG's orientation stay, and the pixels are not re-encoded
    (`storage/…/image/ImageMetadata.java`).
  - **Unused images stay until pruned.** Deleting a product or review,
    or changing its image, leaves the old image in the bucket, as do
    uploads never used; the service can't delete. `scripts/stack.sh
    storage prune-images` lists those last uploaded more than 7 days ago
    (`PRUNE_IMAGES_OLDER_THAN`), and deletes them with `--yes`. Nothing
    schedules it.
- **The journal:** every write records who made it (`app.actor`) and as
  which database role, through a journal function that the API does not
  expose. The journal is append-only, and only its owner can skip it (for a
  migration), so every change the service makes is journaled.
  `storage/src/main/resources/db/schema.sql` is the source of those rules.
- **Exposure:** only the edge, Mailpit, the storage API, its database and
  MinIO ports and Spring Boot Admin are published, and only on `127.0.0.1`.
  The actuator stays inside Docker, where it answers health and info to
  anyone but everything else only to its own account (`ACTUATOR_PASSWORD`),
  which Spring Boot Admin is given; even then it can change only log levels
  and clear caches. `BIND_ADDRESS=0.0.0.0` puts all of them
  but the database and MinIO on the machine's network, Mailpit (no login)
  and the Keycloak admin console among them: do that only on a network you
  mean to expose.
- **Errors** are RFC 9457 problem details, and never include stack traces.
- **Not production-ready as it stands.** This is a development stack: HTTP
  only (Keycloak's `sslRequired` is `external`) and Keycloak in `start-dev`.
  Do not put it on the public internet. A production deployment needs:
  - TLS in front of the edge, and only the edge published;
  - a built Keycloak image (`start --optimized`);
  - real secrets, with `DEV_TOKENS` unset and `JWT_SECRET` empty;
  - a replicated database, scheduled backups (`scripts/stack.sh backup`
    exists; nothing schedules it), and a key rotation plan.

## Also worth knowing

**The data is a graph, served as JSON-LD.** Products, reviews, people and
replies use schema.org terms. Categories are defined terms under
`https://example.com/categories/` (`Audio`, `Books`, `Electronics`, `Home`,
`Outdoors` and `Toys`). Collections are Hydra collections: `totalItems`, `member`, and
`view.next` / `view.previous` links. The context is
`storage/src/main/resources/context.jsonld`, served at `/context.jsonld`.
Aggregate ratings are recomputed in the same transaction as the review
change. Images are objects in MinIO's `review-images` bucket.

**The API describes itself** at **<http://localhost:8080/openapi.yml>**, and
a test checks that the controllers implement exactly the operations in that
OpenAPI document.

**The pages stay client-side after the first load.** List filters live in
the query string. Infinite scroll follows the Hydra `next` link. Next.js keeps
the page you left mounted, so Back returns to a list where it was.
Registration and sign-in are the exception: they leave for Keycloak and come
back with a full load.

## Working on it

### The stack

```sh
scripts/stack.sh all up             # build, start, wait until healthy
scripts/stack.sh all status         # containers and data volumes
scripts/stack.sh all logs           # follow the logs
scripts/stack.sh storage backup     # dump the databases and copy the images bucket
scripts/stack.sh storage restore DIR
scripts/stack.sh storage prune-images      # list unused images; --yes deletes them
scripts/stack.sh all stop           # stop, keep the data
scripts/stack.sh all reset --yes    # stop and delete all the data
scripts/stack.sh identity admin you@example.com   # make an account an administrator
```

It manages the stack one project at a time: `identity`, `storage`,
`presentation`, `edge` or `all`. Backups and restores check every image
copy against the listing it copied from, as `mc mirror` reports success even
when it skipped files, and a restore removes images from the bucket only
once all of the backup's are in it. The databases and the bucket are copied
one after the other, not as one snapshot: back up while nobody is writing. `scripts/stack.sh --help` lists every
command. `DOCKER_CONTEXT` selects the Docker daemon, `COMPOSE_PROJECT_NAME`
the project, and `BIND_ADDRESS` the interface the published ports bind to.

### VS Code

Open the repository root.

- **Tasks** start, stop, restart and follow the logs of each project, back
  up, restore and reset the data, seed the sample product catalog, and run
  each project's tests. The Docker context they use is the
  `reviews.dockerContext` setting (empty: the current context).
- **Presentation: Stop** frees `:3000` for `npm run dev` by moving the edge
  to `:3100`. While it is there, the application at `localhost:3100` is not
  `PUBLIC_URL`, so its sign-in returns to `:3000` and its changes are
  refused (403); **Presentation: Start** puts the edge back on `:3000`.
- **Run and Debug** has two configurations for the storage service:
  *Storage: Debug (Docker services)* runs it on `:8081` (actuator `:9081`)
  against Keycloak, the storage database and MinIO in Docker, and *Storage:
  Debug (Testcontainers)* runs it on `:8082` (actuator `:9082`) against a
  throwaway PostgreSQL and MinIO it starts itself, accepting development
  tokens only. Both read the repository's `.env`, so the passwords and the
  development token secret are the same as the stack's: seed the throwaway
  one with `BASE=http://localhost:8082 storage/scripts/seed-products.sh`.
- **ESLint and TypeScript** use `presentation/`'s configuration and
  TypeScript (`.vscode/settings.json`).
- The storage project targets Java 25. The Java extension and `./mvnw` use
  the JDK that `JAVA_HOME` names, or else the `java` on `PATH`: make that a
  JDK 25 (the repository names no path to one, as it differs between
  machines).

### Tests

| Where | What | Command |
|---|---|---|
| `storage/` | JUnit unit and web-slice tests, plus Testcontainers integration tests against a real PostgreSQL and MinIO, with JaCoCo coverage | `./mvnw test`, `./mvnw verify` (coverage in `target/site/jacoco/index.html`) |
| `presentation/` | Vitest unit tests | `npm test`, `npm run coverage` |
| `presentation/` | ESLint and TypeScript | `npm run lint`, `npm run typecheck` |
| `presentation/` | Playwright tests driving the whole stack in a browser: a reviewer, an owner, the admin role, one review per product, discontinued products, token renewal, page titles, the security headers, and the edge's caching and rate limit | `npm run e2e` (after `npx playwright install chromium`) |

The browser tests need the stack running and seeded with the product catalog
(`storage/scripts/seed-products.sh`), as they browse past the 36th product;
they check both before running, and stop
with what to do. They register real accounts and confirm them through
Mailpit, and fail on any error in a page, such as a hydration mismatch or a
failed request, except the refusals each test declares it provokes
(`allowErrors`, [presentation/README.md](presentation/README.md)).

### Continuous integration

`.gitlab-ci.yml` runs on every merge request and on each branch without one.
Five checks run in parallel, then the browser tests:

| Job | What | Fails on |
|---|---|---|
| `storage` | `./mvnw verify`, with Testcontainers on a Docker-in-Docker service | a failing test, or instruction coverage below 90% (JaCoCo) |
| `admin` | `storage/admin`'s `verify` | a failing test |
| `presentation` | lint, typecheck, Vitest with coverage, `npm audit --omit=dev` | any of them, or a high-severity advisory in a production dependency |
| `scripts` | ShellCheck on every shell script | any finding |
| `compose` | `docker compose config` with generated secrets | an invalid Compose file |
| `e2e` | a fresh stack built from the commit, seeded with the product catalog, then the Playwright tests, a backup and restore of storage, and an unused-image listing | any failure; a `test.only` left in; Playwright never retries |

Test results and coverage appear on the merge request. The browser tests'
report, traces and the stack's logs are kept as artifacts for a week.

The `e2e` job needs a runner tagged `docker-host`: a shell or VM executor
whose own Docker daemon publishes ports on its `localhost`, because the
realm, `PUBLIC_URL` and the sign-in redirects all name
`http://localhost:3000` (Docker-in-Docker publishes them elsewhere). It needs
Docker with Compose v2, bash, openssl, curl, jq, python3, Node 22 and
Chromium's libraries (`npx playwright install-deps chromium`, once, as
root). Runs take turns (`resource_group`), as the stack's ports and subnet
are fixed. No CI variables are needed: each run generates its own secrets
and deletes its stack at the end. Until such a runner has taken the job
once, `e2e` is manual (`when: manual`), so a missing runner does not hold up
the pipeline; remove that line once it has.

### Other things worth knowing

- Keycloak's realm is `identity/realm/reviews-realm.json`. It is imported
  only when it does not exist; after changing it, either apply the change in
  the admin console or reset the identity data
  (`scripts/stack.sh identity reset --yes`).
- The Keycloak login theme is a Tailwind build: its source is
  `identity/themes/styles.css` and `identity/themes/tailwind.config.js`, and
  the built stylesheet is served from the theme's resources.
  `identity/README.md` has the command that rebuilds it (Tailwind 3.4.1,
  through `npx`).
- The `storage` and `storage-admin` images build with JDK 25 and run on
  Java 25 (`eclipse-temurin:25-jdk` and `:25-jre`), as the code targets.

## Troubleshooting

| Symptom | What to do |
|---|---|
| `run scripts/secrets.sh` in a Compose error | The secrets are missing: `scripts/secrets.sh` (it is also run by `scripts/stack.sh`) |
| `secrets: …/.env is a file of its own` | A project directory has its own `.env` instead of the link to the root's, and it holds values the root's does not (an old secret, say). Keep what you need in the root `.env`, then link it: `ln -sf ../.env storage/.env` (or `identity/`, `presentation/`). Copies holding nothing else are replaced by `scripts/secrets.sh` itself |
| The storage service fails at startup: `spring.datasource.password could not be read` (or `spring.flyway.password`, or `secrets: … is empty in .env`) | Outside Docker its passwords come only from `.env`: run `scripts/secrets.sh`, then use the VS Code launch configurations (they read `.env`) or export it before running (`set -a; . ./.env`). An empty value in `.env` fails the same way until it is set |
| `address already in use` | Another program holds the port; set the matching `*_HTTP_PORT` (for example `PRESENTATION_HTTP_PORT=3100`) and `scripts/stack.sh edge up`. Moving the application's port also moves its address: set `PRESENTATION_URL` to match (`http://localhost:3100`) and `scripts/stack.sh presentation up`, and change the `presentation` client's root URL in Keycloak's admin console, which keeps the address the realm was first imported with |
| The sign-in page does not load | The edge is not up: `scripts/stack.sh all status`, then `scripts/stack.sh all up` |
| No email in Mailpit | Check that Mailpit is running (`scripts/stack.sh identity status`) and that `SMTP_HOST` and `SMTP_PORT`, if set in `.env`, still point at it (`mailpit`, 1025) |
| A changed realm has no effect | The realm is imported only once: `scripts/stack.sh identity reset --yes` (deletes the accounts) |
| `./mvnw` complains about the Java version (`release version 25 not supported`) | The service needs JDK 25: set `JAVA_HOME` to one, or put one first on `PATH`; the wrapper does not choose it for you |
| The browser tests cannot find Chromium | `cd presentation && npx playwright install chromium` |
| A seed script says the service `does not accept development tokens` | Add `DEV_TOKENS=true` to `.env`, then `scripts/stack.sh storage up` |
| `storage-db-roles` fails with a password error | The database keeps the superuser's password (`STORAGE_DB_PASSWORD`) it was created with, and `.env` no longer matches it. Put the old one back, or `scripts/stack.sh storage reset --yes`, which recreates the database with the password in `.env` but deletes all the products, reviews and images (back them up first). The owner's and the service's passwords (`STORAGE_DB_OWNER_PASSWORD`, `STORAGE_DB_SERVICE_PASSWORD`) are set again on every `scripts/stack.sh storage up` |
| `storage-minio-init` fails with `The Access Key Id you provided does not exist` or a signature error | MinIO keeps the root password (`MINIO_ROOT_PASSWORD`) it was first started with, and `.env` no longer matches it. Put the old one back, or back up, then `scripts/stack.sh storage reset --yes` and restore. The service's own secret (`MINIO_IMAGES_SECRET_KEY`) is set again on every `scripts/stack.sh storage up` |
| Signing in from `http://127.0.0.1:3000` lands on `localhost`, or saving answers 403 | The application has one address, `PUBLIC_URL` (from `PRESENTATION_URL`, default `http://localhost:3000`): browse it there, or set `PRESENTATION_URL` to the address you use and `scripts/stack.sh presentation up` |
| Spring Boot Admin shows `storage` as DOWN or OFFLINE, or its pages answer 401 | The storage service reads `ACTUATOR_PASSWORD` when it starts, and gives it to Spring Boot Admin when it registers. After changing it in `.env`, `scripts/stack.sh storage up` |
| `stack: … images were not copied` or `did not reach the bucket` | A backup or restore copied fewer images than it listed (an unreadable file, a full disk, MinIO restarting). Nothing was lost: a backup stays in `images.partial/`, and a restore has removed nothing from the bucket. Fix the cause and run it again |

## Further reading

- [identity/README.md](identity/README.md): the realm, the client, the theme,
  the administrators, the backup and reset commands.
- [storage/README.md](storage/README.md): the API, its schema, the
  authorization rules, the caching and concurrency model, and the monitoring.
- [presentation/README.md](presentation/README.md): the application's
  structure, the session, the proxy, the client address handling and the
  Docker build.
- [edge/README.md](edge/README.md): the rate limits, the caching and the
  request bounds, and the variables behind them.
