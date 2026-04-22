# Archi Cooperative Modelling Spec Pack (Kafka + Neo4j + Quarkus + Archi Plugin)

This pack summarizes the current design we discussed for **cooperative (shared live model) modelling** with Archi:
- **Clients:** Archi + a custom **client plugin** (Eclipse RCP/EMF) that captures local changes, sends operations, and applies remote operations.
- **Server:** a new **Collaboration Server** (Quarkus) as the **authority** for validation, ordering (revisions), idempotency, and lock leases.
- **Messaging:** **Kafka** distributes accepted operation batches, locks, and presence events.
- **Repository:** **Neo4j** stores both:
  1) **Materialized current model state**, and
  2) **Append-only op-log** (commits + ops) for audit and rebuild/time-travel.

A key decision: **Archi-specific notation is opaque** to the system as a whole:
- The server and Neo4j treat notation payloads as blobs (`notationJson`).
- Only the client plugin interprets `notationJson` via a NotationSerializer/Deserializer.

## Contents
- `architecture.md` — overall architecture and data flows
- `codex_prompt.txt` — a drop-in prompt you can paste into OpenAI Codex
- `dev-setup.md` — local Java development setup using Docker Compose (Neo4j + Kafka)
- `server/` — Quarkus collaboration server skeleton (`/models/{modelId}/stream`)
- `schemas/` — JSON schemas (common, op-batch, ops, lock-event, presence, notation contract)
- `neo4j/` — Cypher schema for constraints/indexes + suggested graph model
- `kafka/` — topic naming/partitioning guidance
- `notation_mapping.md` — mapping tables from Archi EMF interfaces to `notationJson`
- `sanity-checklist.md` — repeatable manual sync validation flow
- `scripts/check-collab-sanity.sh` — log analyzer for critical sync/integrity issues

## Current Behavior
1) Models are provisioned explicitly through the admin API/catalog before use.
2) Client connects to server and requests checkout (snapshot or snapshot+delta) for an existing model.
3) Unknown `modelId` values are rejected; clients cannot implicitly create models by joining or submitting ops.
4) The linear timeline supports immutable named tags; clients can pull either `HEAD` or a tagged historical snapshot. Tag deletion is disabled by default.
5) Prefer descriptive tag names that remain meaningful outside the codebase, for example `v1.2`, `milestone-3`, or `approved-2026-03-08`.
6) Tagged snapshots are read-only; collaborative writes continue only on `HEAD`.
7) Admin export/import is package-based: export includes model metadata, op-log replay history, current snapshot, and tags; import preserves tags and rejects existing models unless `overwrite=true`.
8) Per-model ACLs can restrict model read/write/admin access to specific users; when present, those ACLs override the generic reader/writer roles for that model.
9) Client sends op batches: `SubmitOps { baseRevision, opBatchId, ops[] }` where idempotency is scoped by `modelId`.
10) Server validates + checks locks, assigns monotonically increasing revisions, persists in Neo4j (op-log + materialized state), publishes to Kafka, and broadcasts to all subscribed clients.
11) Clients apply received ops with echo suppression.
12) Locks are lease-based and required for noisy notation edits (move/resize/bendpoints/style).

## Notes
- Ops are event-sourced; initial implementation uses **full replacement** for notation updates (`UpdateViewObjectOpaque`, `UpdateConnectionOpaque`).
- One Kafka partition per model ops topic is recommended to preserve total order.

## Local Validation
- Fast hardening and parity checks:
  - `./scripts/validate-local.sh fast`
- Full client + server gate:
  - `./scripts/validate-local.sh gate`
- Full gate with local Kafka/Neo4j-backed integration coverage:
  - `./scripts/validate-local.sh infra`
- Full gate with websocket end-to-end coverage as well:
  - `./scripts/validate-local.sh ws`

The scripts above are the supported release gate entrypoints.

This project was created using Codex, but the process has been highly iterative with a lot of trials, fails and sequential human decisions. I would not have been able to make progress like this without my Codex "team", but I have been along the whole trip.

