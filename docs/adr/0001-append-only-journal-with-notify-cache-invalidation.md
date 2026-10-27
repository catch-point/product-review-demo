# ADR 0001: Append-only audit journal with Postgres LISTEN/NOTIFY cache invalidation

## Status
Accepted

## Context
The storage service caches rows in-memory (Caffeine) for performance. Changes can come from:
- The service itself (HTTP API writes)
- Other processes (migrations, manual psql changes)
- Multiple service instances in the future

We need strong auditability and cache consistency across all writers.

## Decision
- Create an append-only `journal` table with triggers on all exposed tables/relations.
- Record `op`, `table_name`, `pk`, `old_row`, `new_row`, `actor`, `db_role`, `txid`, `happened_at`.
- Block direct writes/updates/deletes to `journal` via a guard trigger.
- Publish journal entries via `pg_notify('journal', ...)` after insert; include keys needed for cache invalidation.
- Service listens on the channel and invalidates relevant cached entries.
- Only the journal owner (migration role) may skip journaling via `app.skip_journal`; the app service role cannot.
- Track `app.actor` per transaction for attribution.

## Consequences
- **Pros**: Complete audit trail, correct cache invalidation even for external writes, traceable by txid/actor.
- **Cons**: Trigger overhead on every write, notify payload size constraints (mitigated by `journal_refs()`), requires DB support for LISTEN/NOTIFY.
- **Notes**: Journal is not exposed via API; access is restricted by role separation.
