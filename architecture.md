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
- Idempotency: dedupe by `(modelId, opBatchId)`
- Persistence:
  - append to op-log (Commit + Op)
  - apply to materialized state
  - update `Model.headRevision`
- Publish accepted op batches to Kafka
- Stream accepted op batches to subscribed clients

Authorization shape:
- A reverse proxy may handle authentication and coarse route filtering.
- Quarkus is the in-process PEP and must enforce model/tag/admin policy decisions at REST and websocket boundaries.
- The current PDP is project-specific and in-process, intentionally narrow to this domain instead of generic XACML wiring.
- Identity resolution is pluggable:
  - `bootstrap` mode reads `X-Collab-User` / `X-Collab-Roles` for REST and `user` / `roles` websocket query params for local/dev use.
  - `proxy` mode reads trusted forwarded headers for both REST and websocket handshake requests.
  - `oidc` mode reads the authenticated principal and role membership that Quarkus already resolved for the request or websocket upgrade.
- In standalone `oidc` deployments, Quarkus can validate bearer JWTs directly and the PEP consumes the resulting principal and role mapping without any reverse proxy dependency.
- Provider-specific role names are normalized into the canonical internal roles (`admin`, `model_reader`, `model_writer`) before policy evaluation, so the PDP remains transport- and provider-agnostic.
- The same `oidc` path can be backed either by local JWT verification (`quarkus-smallrye-jwt`) or an external OIDC provider (`quarkus-oidc`); the PEP still only consumes normalized principal + role data.
- External OIDC provider support is opt-in; the extension is present but disabled until `quarkus.oidc.enabled=true` and provider settings are supplied.
- Concrete provider examples now live in `server/examples/keycloak-oidc.example.properties` and `server/examples/auth0-oidc.example.properties` to keep provider-specific wiring out of the PDP itself.
- The current admin UI can supply the bootstrap headers directly for local/dev use; in proxy mode it should be served through the trusted proxy and rely on forwarded identity instead.
- `oidc` mode does not require a reverse proxy, but it does require a Quarkus auth mechanism such as OIDC/JWT to populate the principal and roles.
- Model ACLs are stored per model and are used for model-scoped read/write/admin decisions when configured.
- Concrete reverse-proxy forwarding examples now live in `server/examples/nginx-proxy-mode.example.conf`, `server/examples/caddy-proxy-mode.example.Caddyfile`, and `server/examples/traefik-proxy-mode.example.yml`; they keep the proxy concern limited to trusted identity forwarding while the Quarkus PEP remains the enforcement point.
- Catalog-wide admin actions remain global-admin-only; model-scoped admin actions can be delegated to model admins.
- Admin audit events are emitted as stable JSON log payloads. Treat them as structured events, send them to a dedicated audit sink when possible, and keep retention/polling settings aligned with expected admin dashboard traffic.
- A concrete Vector example now lives in `server/examples/vector-audit-log.example.toml` to split `admin_audit` and `ws_audit` into separate structured streams without relying on message-text parsing downstream.

### Kafka
Topics per model:
- `archi.model.<modelId>.ops` (accepted operation batches; **1 partition** recommended for total order)
- `archi.model.<modelId>.locks` (lock events; short retention)
- `archi.model.<modelId>.presence` (presence; very short retention)

### Neo4j
Two subgraphs:
1) **Materialized state** graph (current model)
2) **Op-log** graph (append-only commits + ops)

Repository boundaries after refactor:
- `Neo4jRepositoryImpl` coordinates repository-facing operations and lifecycle.
- `Neo4jOpLogSupport` owns commit/op persistence.
- `Neo4jMaterializedStateSupport` owns materialized-state mutation and LWW/OR-Set merge behavior.
- `Neo4jReadSupport` owns snapshot export, consistency checks, and checkout delta reads.
- `Neo4jCompactionSupport` owns metadata compaction.
- `NotationMetadata` is the shared source of truth for notation field validation and persisted field clocks.

Maintainer invariants:
- Materialized entity identity is scoped by `(modelId, id)`, not by `id` alone.
- Commit/idempotency identity is scoped by `(modelId, opBatchId)`.
- Model tags are part of the model timeline; future export/import or migration flows must preserve them.
- Authorization is deny-by-default when enabled; admin actions require the configured admin role through the Quarkus PEP.
- Admin-created models seed the creating user as the initial model admin/writer/reader ACL entry.
- Supported notation keys and persisted notation field clocks must be defined through `NotationMetadata`.
- Repository write failures must fail fast; do not log-and-continue on append/apply/head-update paths.

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

### Offline outbox contract
- The client keeps a per-session bounded outbox (`MAX_OUTBOX_SIZE=1000`) for `SubmitOps` envelopes that cannot be sent immediately.
- Queue ordering is FIFO per model and preserved across reconnects through durable persistence (`*.outbox.properties` in the collaboration cache directory).
- During replay, the client drains one queued entry at a time for the current model:
  - `baseRevision` is rebased to the latest known server revision before send,
  - an entry is removed from the queue only after server `OpsAccepted` for the same `(modelId, opBatchId)`,
  - send failure keeps the entry at the queue head (no tail re-enqueue), preventing offline/online flapping from reordering queued intent.
- If server ack does not arrive in time, replay retries from the same queue head (safe via model-scoped idempotent `(modelId, opBatchId)`).
- Replay failures use deterministic exponential backoff (250ms, 500ms, 1000ms, ... capped at 5000ms) with no jitter.
- Poison handling: a queued entry that fails replay 5 times is dropped and logged so subsequent queued intent can continue replay.
- Reconciliation conflict policy:
  - `PRECONDITION_FAILED` or `LOCK_CONFLICT` for queued replay is treated as stale/conflicting local intent,
  - the conflicting head entry is dropped deterministically,
  - conflict is surfaced via `SubmitConflictListener` for UI/log handling (not silent), currently shown as a temporary status-line error banner and exposed via Tools -> "Show Last Collaboration Conflict".
- While a replay drain is active (or queued entries already exist for the model), new `SubmitOps` are appended to the outbox and sent through the same ordered drain path.
- If the outbox is full, the oldest queued entry is dropped and the drop is logged.
- Model-switch semantics:
  - queued entries are scoped by `modelId`;
  - replay drains only entries for the currently joined model;
  - a `SubmitOps` whose `payload.modelId` does not match the active socket model is queued (not sent) until that model is joined.

### Apply remote ops
1. Client receives `OpsBroadcast` (from WS stream).
2. Plugin applies ops into EMF model in a safe UI/EMF context.
3. Echo suppression prevents rebroadcast of remote-applied changes.

## Concurrency / locks (MVP)
- Locks are lease-based (TTL).
- Locks required for: `UpdateViewObjectOpaque`, `UpdateConnectionOpaque`, and other view-notation edits.
- Semantic edits can be locked too, or handled optimistically in later iterations.

## Notation semantics
- `notationJson` keys are explicitly schema-whitelisted for view objects/connections (`additionalProperties: false`).
- Unknown notation keys are rejected by server precondition validation (`PRECONDITION_FAILED`).
- Server/client apply per-field LWW merge for supported notation fields; updates are still submitted as full object payloads.
- Collection-like notation domains beyond current schema (except `bendpoints` as LWW value) are explicitly unsupported in this phase.
