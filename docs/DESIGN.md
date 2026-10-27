# Design Highlights

This project showcases several intentional design decisions:

## Architecture
- **BFF Pattern**: Next.js presentation server keeps tokens in HttpOnly cookies, proxies to storage API, and enforces role-based routing.
- **Service Separation**: Storage, Identity (Keycloak), Presentation, Edge (nginx) as independent services with fixed-address trust model.
- **Role Separation**: Database roles split between migrator (`reviews_owner`) and app (`reviews_service`) with strict capabilities.

## Data & Consistency
- **JSON-LD/Hydra**: Schema.org terms, Hydra collections with paging and search templates.
- **Optimistic Concurrency**: ETags + `If-Match` prevent lost updates (see [ADR 0002](adr/0002-optimistic-concurrency-with-etags.md)).
- **Audit Trail**: Append-only journal with triggers and NOTIFY-based cache invalidation (see [ADR 0001](adr/0001-append-only-journal-with-notify-cache-invalidation.md)).
- **Soft Delete**: Products can be discontinued while preserving reviews/replies (see [ADR 0003](adr/0003-soft-delete-products-with-review-retention.md)).

## Security
- **OIDC with PKCE**: Authorization code flow as confidential client.
- **Trusted Proxies**: Fixed IPs for presentation/edge determine client address.
- **Rate Limiting**: Per-client and per-person limits at edge and application level.
- **Least Privilege**: App role cannot migrate, cannot skip journal, cannot bypass RLS/superuser-like capabilities.

## Testing
- **Unit**: Vitest for presentation utilities/components.
- **Integration**: Spring Boot tests with Testcontainers for storage.
- **E2E**: Playwright journeys covering registration, ownership, reviews, replies, and discontinued flows.
