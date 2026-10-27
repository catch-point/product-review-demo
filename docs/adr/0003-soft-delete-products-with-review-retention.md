# ADR 0003: Soft-delete products with review and reply retention

## Status
Accepted

## Context
Products may be discontinued by owners, but reviews and replies have value and should remain accessible for transparency. We need to hide discontinued products from discovery while preserving the review graph.

## Decision
- Add `product."discontinuedDate"` (internal).
- `DELETE /products/{id}` by owner sets `discontinuedDate` (soft delete). Repeated deletes return 204 without preconditions.
- Discontinued products are hidden from listings, direct reads, review collections, ownership listings, and new review creation (return 404).
- Existing reviews and replies remain readable via their own IRIs; authors can still edit/delete their reviews; owners can still reply to reviews on discontinued products.
- Non-owners/ownerless/unknown get 404 for delete to avoid leaking existence.

## Consequences
- **Pros**: Preserves review history and community content, clean UX for discovery, clear ownership semantics.
- **Cons**: Need to filter discontinued products in all relevant queries/caches, special handling in services/repositories.
- **Notes**: Uses uncached `findAny` for hidden rows where necessary; cached active lists exclude discontinued products.
