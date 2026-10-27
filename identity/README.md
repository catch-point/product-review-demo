# Identity: accounts, sign-in and roles

[Keycloak](https://www.keycloak.org) holds the accounts: people register and
sign in with their email and password, choosing whether they review
products or own them. It issues the tokens the storage service accepts,
carrying that role and whether the person is a site administrator.

What each person may do with those roles is decided by the storage service:
owners add products and change only their own; reviewers review; only a
review's or comment's author changes it (see "Authorization" in
`../storage/README.md`).

Keycloak runs in Docker with its own PostgreSQL database. Mailpit catches the
email it sends in development.

| File | Purpose |
|---|---|
| `realm/reviews-realm.json` | The `reviews` realm, imported when it does not exist yet |
| `configure.sh` | What the import cannot set, applied after Keycloak starts |
| `themes/reviews/login` | The sign-in and registration pages, in the style of the rest of the site |
| `compose.yaml` | Keycloak, its PostgreSQL database, and Mailpit |

## Run

From the repository root:

```sh
scripts/stack.sh identity up    # Keycloak on :8180, Mailpit on :8025
```

Keycloak is not published by this project's own `compose.yaml`: browsers
reach it on :8180 through the edge (`edge/`, nginx), which the wrapper
starts too. It also runs `keycloak-config`, the one-shot container that
applies what the realm import cannot set, once the other services are
healthy: `docker compose up -d --wait` would count that container exiting
as a failure.

To run the whole application (this, `storage/` and `presentation/`), use
`scripts/stack.sh all up` from the repository root.

Secrets — the database and admin passwords and `PRESENTATION_CLIENT_SECRET` —
are generated into the repository root's `.env` by `scripts/secrets.sh` on
first use (git-ignored; symlinked here as `./.env`).

| Variable | Default | |
|---|---|---|
| `KEYCLOAK_URL` | `http://localhost:8180` | The URL browsers use, and so the tokens' issuer |
| `KEYCLOAK_HTTP_PORT` / `MAILPIT_HTTP_PORT` | `8180` / `8025` | Host ports |
| `KEYCLOAK_ADMIN_USERNAME` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `.env` | The admin console, http://localhost:8180/admin |
| `KEYCLOAK_DB_PASSWORD` | `.env` | |
| `PRESENTATION_URL` | `http://localhost:3000` | The web application, where Keycloak returns people |
| `PRESENTATION_CLIENT_SECRET` | `.env` | The `presentation` client's secret (its `KEYCLOAK_CLIENT_SECRET`) |
| `SMTP_HOST` / `SMTP_PORT` | `mailpit` / `1025` | Where Keycloak sends email |
| `ADMIN_EMAILS` | | Registered accounts given the `admin` role each time the stack is brought up (`keycloak-config` runs then, and only for accounts that exist already; comma-separated) |

In VS Code (with the repository root open), the `Identity: …` tasks start,
stop and restart Keycloak and Mailpit, follow their logs, make an
administrator, and back up, restore or reset Keycloak's database. They run
`scripts/stack.sh identity <command>` against the root `compose.yaml`.

## Keycloak

The realm `reviews` (`realm/reviews-realm.json`):

- **Registration** uses the email as the username and asks for a name and a
  role: `reviewer` or `owner`, a user-profile attribute shown as radio
  buttons. Users may set it when they register, but they have no account
  management role (`configure.sh` removes `manage-account`), so neither the
  account console nor its REST API can change it afterwards.
- **Email** must be verified before signing in completes, and passwords can
  be reset by email. In development both arrive in Mailpit,
  http://localhost:8025.
- **Passwords**: at least 8 characters, not the email. Brute-force detection
  locks an account after 10 failures.
- **Client addresses.** Keycloak is not published: browsers reach it
  through the edge (`edge/`, nginx) on :8180, which limits each address
  (sign-in and registration forms most). `KC_PROXY_HEADERS=xforwarded` with
  `KC_PROXY_TRUSTED_ADDRESSES` set to the edge's and the presentation
  server's fixed addresses on the `backchannel` network. The edge names
  each browser's address in `X-Forwarded-For`, and so do the presentation
  server's calls (code exchange, token renewal, sign-out). Keycloak's
  events and sessions then record it. From any other address the header is
  ignored.
- **The client `presentation`** (the web application) signs people in with
  the authorization code flow and PKCE, as a confidential client. Its access
  tokens carry the `role` claim, the realm roles in `roles`, and the
  audience `storage`, which the storage service requires, with the verified
  `email` that names the person.
- **Administrators** have the realm role `admin`, kept for future use: the
  storage service reads it but allows them nothing more yet (no one sees
  others' emails). Nobody can give it to themselves: grant it in
  the admin console (Users → the account → Role mapping), with
  `scripts/stack.sh identity admin EMAIL` once the account exists, or by
  listing the email in `ADMIN_EMAILS`. It applies from the person's next
  token, within 5 minutes.
- **Sessions** last up to 10 hours (8 idle); access tokens, 5 minutes.

The import only creates a realm that does not exist. After changing
`reviews-realm.json`, reset the identity data (`scripts/stack.sh identity
reset --yes`) or apply the change in the admin console. The theme's
stylesheet is built with Tailwind 3.4.1, from the repository root:

```sh
npx tailwindcss@3.4.1 -c identity/themes/tailwind.config.js -i identity/themes/styles.css \
  -o identity/themes/reviews/login/resources/css/styles.css --minify
```

That rebuilds the committed stylesheet byte for byte; rebuild after changing
the templates' classes or `styles.css`. Development mode rereads the
templates on every request.
