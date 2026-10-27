# Edge: the way in

nginx (`default.conf.template`) in front of the web application, on :3000,
and Keycloak, on :8180. Their own ports are not published, so every browser
request passes through it. It is started with them
(`scripts/stack.sh identity|presentation|all up`), or alone with
`scripts/stack.sh edge up`.

## What it does

- **Limits each client address.** The address is the connection's: nothing
  in front is trusted, so an `X-Forwarded-For` a client sends is ignored.
  So whatever hands connections to the edge decides the addresses it sees.
  Browsers on this machine reach it through Docker's port forwarding, which
  connects from the Docker network's gateway (`172.31.250.1`): locally they
  all share one allowance, which the browser tests' burst spends for
  everyone. Behind a load balancer or TLS proxy every visitor would be that
  proxy, until nginx is told to believe its `X-Forwarded-For`
  (`set_real_ip_from` and `real_ip_header`).
  Each zone has a steady rate and a burst served at once; past them it
  answers 429 `application/problem+json` with `Retry-After: 5`.

  | Zone | Applies to | Default |
  |---|---|---|
  | `reads` | every request | 100 a second, burst 150 |
  | `writes` | anything but `GET`, `HEAD`, `OPTIONS` | 10 a second, burst 60 |
  | `signin` | Keycloak's sign-in, registration and password-reset form posts | 5 a second, burst 60 |
  | connections | open at once | 50 |

  The storage service limits each signed-in person too, and Keycloak locks
  an account after repeated failed passwords. This limit covers what those
  cannot see, such as many accounts tried from one address.
- **Bounds slow and large requests:** 10 seconds for headers and between
  body reads, and bodies of at most 6 MB (an image upload).
- **Caches** (`X-Cache-Status` says whether a response was a `HIT`):
  - uploaded images (`/api/storage/images/…`) and the application's build
    files (`/_next/static/…`). Both are named by their content and say
    `immutable`, so they are kept for everyone, signed in or not. A
    response that sets a cookie never is;
  - storage reads by people not signed in (no `session` cookie): kept for
    2 seconds, so a flood of them reaches storage once a moment. Storage
    marks what is the same for everyone `no-cache` (revalidate), which the
    edge answers itself for that long; what it marks `private` or
    `no-store` (personal, or saying nothing, as errors do) is never kept.
    They are keyed by `Accept` too. Signed-in reads always go through.

  The cache is in the container, so a restart empties it.
- **Names the client** in `X-Forwarded-For` to the presentation server and
  Keycloak. Each believes the header from the edge's fixed address,
  `EDGE_ADDRESS` (`172.31.250.11`) on the `backchannel` network, and from
  no one else. The presentation server passes it on to the storage service.
- **Drops what a client says about the address it reached**
  (`X-Forwarded-Host`, `-Port`, `-Prefix` and `Forwarded`). The application
  names itself by its configured `PUBLIC_URL`, so what the edge caches for
  everyone never links to a host a client chose.

## Configuration

| Variable | Default | |
|---|---|---|
| `EDGE_READ_RATE` / `EDGE_READ_BURST` | `100r/s` / `150` | The `reads` zone |
| `EDGE_WRITE_RATE` / `EDGE_WRITE_BURST` | `10r/s` / `60` | The `writes` zone |
| `EDGE_SIGNIN_RATE` / `EDGE_SIGNIN_BURST` | `5r/s` / `60` | The `signin` zone |
| `EDGE_CONNECTIONS` | `50` | Connections open at once per address |
| `EDGE_MICROCACHE` | `2s` | How long anonymous storage reads are kept |
| `EDGE_ADDRESS` | `172.31.250.11` | Its address on the `backchannel` network |
| `PRESENTATION_HTTP_PORT` / `KEYCLOAK_HTTP_PORT` | `3000` / `8180` | Where it is published (on `BIND_ADDRESS`) |

## Locally

Through Docker's published ports, every browser arrives from the network's
gateway, so on one machine they all share one address and its limits. The
end-to-end tests run as one address: `e2e/edge.spec.ts` uses up its allowance
on purpose, so Playwright runs it after the others.

Editing `default.conf.template` needs `docker compose up -d --force-recreate
edge`. It is mounted as a single file, and editors that replace the file
leave the container with the old one.
