# ADR 0002: Optimistic concurrency with ETags and If-Match

## Status
Accepted

## Context
Multiple users may edit the same resource concurrently. We need to prevent lost updates without heavy locking of entire tables for long periods.

## Decision
- Return ETags on GET responses for mutable resources.
- Require `If-Match` on PUT/DELETE (configurable via `reviews.concurrency.require-preconditions`).
- On write, re-fetch/check current state with appropriate locking (e.g., `SELECT ... FOR UPDATE`) and compare preconditions.
- Return 412 Precondition Failed if stale, 428 Precondition Required if missing when required.
- Drop/refresh caches on successful writes.

## Consequences
- **Pros**: Safe concurrent edits, standard HTTP semantics, good UX for detecting conflicts.
- **Cons**: Clients must handle preconditions; extra read/lock on writes.
- **Notes**: Ownership checks are performed after locking to avoid leaking information about ownership changes.
