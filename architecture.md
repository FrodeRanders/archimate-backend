# Architecture

This document explains how the collaboration solution is structured, where state lives, and how the major flows work.

## Purpose

The system lets multiple Archi clients collaborate on the same model timeline.

It does this by combining:

- a local Archi client plugin that captures and applies model changes
- a Quarkus collaboration server that validates, orders, persists, and broadcasts changes
- Kafka for ordered model event fan-out
- Neo4j for the materialized model graph, op-log, tags, and merge metadata

The model is intentionally linear:

- one `HEAD`
- no branches
- immutable tags as named historical points

## System view

At a high level:

1. a user edits a model in Archi
2. the client plugin captures local EMF changes and turns them into collaboration ops
3. the server validates the op batch and assigns authoritative revisions
4. the server persists the batch, updates the materialized graph, and broadcasts it
5. other clients apply the accepted ops into their local Archi model

There are two kinds of state in play:

- local working state inside each Archi client
- authoritative shared state on the collaboration server

## Main components

## Archi client plugin

Location:

- `client/com.archimatetool.collab`

Responsibilities:

- connect to the collaboration websocket
- capture local semantic and notation edits from EMF
- convert local changes into `SubmitOps`
- maintain a bounded offline outbox
- apply remote snapshots and broadcasts into the local Archi model
- manage local collaboration session state such as:
  - current model id
  - current ref (`HEAD` or tag)
  - auth token
  - last known revision

Important classes:

- `CollabSessionManager`
  - websocket lifecycle
  - outbox handling
  - join/rejoin decisions
  - local submit tracking
- `EmfChangeCapture`
  - observes local EMF changes
  - maps them to collaboration operations
- `OpMapper`
  - builds wire-format op batches
- `RemoteOpApplier`
  - applies snapshots and remote broadcasts into the local model
- `ModelCollaborationController`
  - attaches and detaches the collaboration plumbing from a model

Important client design choices:

- local edits are not immediately authoritative
- `OpsAccepted` from the server is the commit acknowledgment
- the client now suppresses application of its own echoed `OpsBroadcast` messages by `opBatchId`
- tagged refs are read-only in the client
- `Tools > Resynchronize Model` can force a cold snapshot rebuild of the local projection

## Collaboration server

Location:

- `server/src/main/java`

Responsibilities:

- websocket and REST entry points
- authorization
- model registration rules
- validation and precondition checks
- revision assignment
- idempotency
- persistence to Neo4j
- broadcast through Kafka and websocket sessions
- admin export/import, tags, ACLs, and diagnostics

Important entry points:

- `CollaborationEndpoint`
  - websocket endpoint for join, submit, lock, presence
- `ModelStateEndpoint`
  - snapshot and rebuild endpoints
- `AdminEndpoint`
  - model catalog, ACL, tags, export/import, audit/diagnostic endpoints

Core service:

- `CollaborationService`

This is the central coordination layer. It:

- validates client actions against model and ref state
- assigns revision ranges
- normalizes causal metadata
- prepares batches for persistence
- handles tags, export/import, rebuild, and admin views

## Authorization architecture

Authorization is enforced in Quarkus.

The server uses:

- a Quarkus-side PEP
- a project-specific in-process PDP

The current PDP is intentionally narrow to this domain. It is not generic XACML infrastructure.

### Identity modes

Identity resolution is pluggable:

- `bootstrap`
  - local/dev mode
  - REST: `X-Collab-User`, `X-Collab-Roles`
  - websocket: `user`, `roles`
- `proxy`
  - trusted forwarded headers from a reverse proxy
- `oidc`
  - direct principal/role resolution through Quarkus auth
  - can use local JWT verification or an external OIDC provider

Role names are normalized into canonical internal roles:

- `admin`
- `model_reader`
- `model_writer`

### Policy model

The PDP decides on actions such as:

- admin catalog actions
- model join
- snapshot read
- submit ops
- locks and presence
- tag actions

Model-scoped ACLs are stored per model and, when configured, override generic reader/writer role checks for that model.

## Persistence architecture

The server persists to Neo4j using a split repository layer.

Main coordinating repository:

- `Neo4jRepositoryImpl`

Supporting collaborators:

- `Neo4jOpLogSupport`
  - commit/op persistence
- `Neo4jMaterializedStateSupport`
  - materialized graph mutation
  - folder handling
  - notation merge logic
  - tombstones and property clocks
- `Neo4jReadSupport`
  - snapshot export
  - checkout delta reads
  - consistency checks
- `Neo4jCompactionSupport`
  - metadata compaction

Supporting invariant source:

- `NotationMetadata`

This is the shared source of truth for supported notation fields and persisted notation merge metadata.

### What is persisted

Neo4j stores:

- model catalog metadata
- per-model ACL arrays
- materialized current state
- folders and folder memberships
- view hierarchy and diagram notation
- op-log commits and ops
- immutable tags with stored tagged snapshots
- tombstones and property clocks for merge behavior

The current graph structure is documented in:

- `neo4j/graph-model.md`

## Messaging architecture

Kafka is used as the ordered fan-out path for accepted collaboration batches.

Topic pattern:

- `archi.model.<modelId>.ops`
- `archi.model.<modelId>.locks`
- `archi.model.<modelId>.presence`

Important behavior:

- operation topics should remain single-partition per model for total order
- the collaboration server publishes accepted batches after persistence
- websocket clients receive broadcasts from the session registry / consumer path

## Data and state model

## Linear model timeline

Every model has one linear revision timeline.

There is:

- one moving `HEAD`
- zero or more immutable tags

There are no branches.

### Revisions

Clients submit a batch against:

- `baseRevision`

The server assigns an authoritative contiguous revision range to the accepted batch:

- `assignedRevisionRange.from`
- `assignedRevisionRange.to`

If the batch contains `N` ops, it consumes `N` revisions.

This range is used for:

- commit history
- rebuild
- export/import
- per-op causal Lamport defaults

## Snapshot vs op-log

The system keeps both:

- a materialized snapshot-like graph in Neo4j
- an append-only op-log

Why both exist:

- the materialized graph makes joins, exports, and admin inspection fast
- the op-log preserves the authoritative committed history

This is why export packages contain:

- current snapshot
- full op batches
- tag snapshots

## Tags

Tags are immutable named revision points.

Behavior:

- `HEAD` is writable
- tags are read-only
- pulls/checkouts can target `HEAD` or a tag
- tag snapshots are stored server-side

Tags are part of the model timeline and must survive export/import.

## Folders

Model-tree folders are first-class synchronized state.

Important rules:

- root folders have stable synthetic ids
- user folders have immutable ids
- rename is allowed
- object placement in folders is explicit
- cross-root folder moves are rejected
- non-empty folder delete is rejected

This avoids drift between clients and preserves Archi model-tree structure.

## Main flows

## Join / checkout

1. client opens websocket
2. client sends `Join`
3. server resolves:
   - model existence
   - authorization
   - requested ref (`HEAD` or tag)
4. server responds with:
   - `CheckoutSnapshot`, or
   - `CheckoutDelta`
5. client applies snapshot/delta into the local Archi model

Important rule:

- `Connect Collaboration...` and explicit cold-start paths use a snapshot-first rejoin because delta rejoin is unsafe for arbitrary non-server-backed local models

## Edit / submit

1. user edits in Archi
2. `EmfChangeCapture` turns EMF notifications into ops
3. client sends `SubmitOps { baseRevision, opBatchId, ops }`
4. server:
   - validates preconditions
   - checks locks
   - assigns revision range
   - normalizes causal metadata
   - appends commit/op log
   - applies to materialized state
   - updates head revision
   - publishes to Kafka
   - emits `OpsAccepted`
   - broadcasts accepted ops
5. other clients apply the broadcast

## Offline outbox

The client keeps a bounded persistent outbox per model.

Key guarantees:

- FIFO replay per model
- rebasing to latest known revision before replay send
- removal only after matching `OpsAccepted`
- deterministic retry with capped exponential backoff
- conflicting stale replay head entries are dropped on:
  - `PRECONDITION_FAILED`
  - `LOCK_CONFLICT`

This is intentionally conservative. The goal is deterministic recovery, not hidden magic.

## Apply remote ops

Remote ops are applied into the local EMF model under a remote-apply guard so they do not get rebroadcast as local edits.

The client also:

- defers dependency-sensitive ops when needed
- restores folder structure before folder memberships during snapshot apply
- restores views before view objects and connections

## Export / import

Admin export packages include:

- model metadata
- ACL data
- current materialized snapshot
- full op-log history
- immutable tags and tag snapshots

Import behavior:

- validates format and required metadata
- rejects overwrite unless explicitly requested
- rejects overwrite while active sessions exist
- restores op-log and materialized state
- restores tags and ACLs

Detailed package structure is documented in:

- `server/EXPORT_FORMAT.md`

## Concurrency and merge semantics

## Locks

Lease-based locks are used for notation-sensitive edits such as:

- `UpdateViewObjectOpaque`
- `UpdateConnectionOpaque`

This reduces destructive concurrent diagram edits while keeping semantic collaboration workable.

## Merge semantics

The system uses a mix of:

- LWW field merges
- tombstone protection
- OR-set style property/member behavior where appropriate

Examples:

- notation fields use per-field Lamport LWW semantics
- entity recreates are checked against tombstones
- folder semantics are explicitly tested for convergence and LWW rename behavior

## Admin UI

The admin UI is based on Svelte.

Location:

- `admin-web/`

Served by Quarkus at:

- `/admin-ui/`

It is organized into focused routes:

- overview
- models
- versions
- access
- sessions
- audit

## Diagnostics and audit

The system emits structured logs for:

- `admin_audit`
- `ws_audit`

Admin endpoints also expose diagnostics for:

- current resolved identity
- normalized roles
- active websocket sessions
- audit configuration

The intent is operational visibility, not just developer debugging.

## Maintainer invariants

These rules are easy to violate accidentally and should stay explicit:

- materialized entity identity is `(modelId, id)`
- commit/idempotency identity is `(modelId, opBatchId)`
- tags are immutable named pointers on the same linear timeline
- only `HEAD` is writable
- folder ids are immutable
- cross-root folder moves are invalid
- notation field definitions must flow through `NotationMetadata`
- repository write failures must fail fast
- admin authorization is deny-by-default when enabled

## Tradeoffs

This architecture chooses:

- linear history instead of branching
- explicit persisted materialized state instead of replay-on-every-read
- explicit folder placement instead of client-local organization
- a project-specific PDP instead of generic policy machinery
- deterministic offline replay instead of optimistic hidden reconciliation

Those choices keep the system explainable and operationally tractable at the cost of some flexibility.
