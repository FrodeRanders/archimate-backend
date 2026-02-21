# Architecture

## Components
### Archi Client Plugin
- Observes EMF changes (semantic + notation)
- Builds operations and sends them to the Collaboration Server
- Receives ordered operations and applies them to local in-memory EMF model
- Debounces/coalesces noisy notation updates
- Maintains echo suppression during remote apply
- Manages presence and lock UX (auto-lock on drag/resize)

### Collaboration Server (Quarkus)
Authoritative responsibilities:
- Authentication/authorization (can be stubbed initially)
- Session management + model subscriptions
- Validation: referential integrity, type checks
- Concurrency control: lock leases for notation edits (recommended for MVP)
- Revision ordering: assigns monotonically increasing revisions
- Idempotency: dedupe by `opBatchId`
- Persistence:
  - append to op-log (Commit + Op)
  - apply to materialized state
  - update `Model.headRevision`
- Publish accepted op batches to Kafka
- Stream accepted op batches to subscribed clients

### Kafka
Topics per model:
- `archi.model.<modelId>.ops` (accepted operation batches; **1 partition** recommended for total order)
- `archi.model.<modelId>.locks` (lock events; short retention)
- `archi.model.<modelId>.presence` (presence; very short retention)

### Neo4j
Two subgraphs:
1) **Materialized state** graph (current model)
2) **Op-log** graph (append-only commits + ops)

## Data flows

### Checkout
1. Client connects and sends `Join { modelId, lastSeenRevision? }`.
2. Server responds:
   - `CheckoutSnapshot { headRevision, snapshot }` (e.g. Archi `.archimate` export), OR
   - `CheckoutDelta { fromRevision, toRevision, opBatches[] }` plus an optional snapshot base.
3. Client loads snapshot into a working model and begins subscribing to ops.

### Edit / SubmitOps
1. User edits in Archi.
2. Plugin captures EMF notifications and builds `ops[]` (semantic typed, notation opaque).
3. Plugin sends `SubmitOps { baseRevision, opBatchId, ops[] }`.
4. Server pipeline:
   - validate ops (refs exist, type checks)
   - check lock leases where required
   - assign revision range
   - persist commit + ops to Neo4j op-log
   - apply ops to Neo4j materialized state
   - update model head revision
   - publish op batch to Kafka
   - broadcast op batch to all subscribed clients

### Apply remote ops
1. Client receives `OpsBroadcast` (from WS stream).
2. Plugin applies ops into EMF model in a safe UI/EMF context.
3. Echo suppression prevents rebroadcast of remote-applied changes.

## Concurrency / locks (MVP)
- Locks are lease-based (TTL).
- Locks required for: `UpdateViewObjectOpaque`, `UpdateConnectionOpaque`, and other view-notation edits.
- Semantic edits can be locked too, or handled optimistically in later iterations.

## Opaque notation
- `notationJson` is treated as uninterpreted by server/Neo4j.
- Client plugin must serialize/deserialize all visual state needed for consistent rendering across clients.
- Updates are full replacements initially.
