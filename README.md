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

## MVP Behavior
1) Client connects to server and requests checkout (snapshot or snapshot+delta).
2) Client sends op batches: `SubmitOps { baseRevision, opBatchId, ops[] }`.
3) Server validates + checks locks, assigns monotonically increasing revisions, persists in Neo4j (op-log + materialized state), publishes to Kafka, and broadcasts to all subscribed clients.
4) Clients apply received ops with echo suppression.
5) Locks are lease-based and required for noisy notation edits (move/resize/bendpoints/style).

## Notes
- Ops are event-sourced; initial implementation uses **full replacement** for notation updates (`UpdateViewObjectOpaque`, `UpdateConnectionOpaque`).
- One Kafka partition per model ops topic is recommended for MVP to preserve total order.

## CRDT Gate
- Run the CRDT-focused gate suite (client + server):
  - `./scripts/crdt-gate.sh`
- Run only fast hardening contract checks:
  - `./scripts/check-crdt-hardening.sh`
- Run only schema/server/client notation parity checks:
  - `./scripts/check-notation-parity.sh`
- Include local Kafka/Neo4j-backed convergence tests:
  - `RUN_LOCAL_INFRA_IT=true ./scripts/crdt-gate.sh`
- CI workflows:
  - PR/manual fast gate: `.github/workflows/crdt-gate.yml`
  - nightly/manual local-infra gate: `.github/workflows/crdt-local-infra.yml`

## Branch Protection
- Target branch: `main`
- Enable "Require a pull request before merging".
- Enable "Require status checks to pass before merging".
- Add required status check:
  - `CRDT Gate / crdt-gate`
- Recommended (non-blocking) checks:
  - `CRDT Local Infra / crdt-local-infra` (nightly/manual signal; optional for merge gating due to runtime and infra dependency)
- Optional hardening:
  - Enable "Require branches to be up to date before merging".
  - Enable "Require conversation resolution before merging".
