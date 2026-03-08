# Collaboration Server Skeleton (Quarkus)

This module implements the MVP scaffold from `codex_prompt.txt`.

## Includes

- WebSocket endpoint: `ws://localhost:8081/models/{modelId}/stream`
- REST endpoint: `GET http://localhost:8081/models/{modelId}/snapshot`
- REST endpoint: `POST http://localhost:8081/models/{modelId}/rebuild`
- REST endpoint: `GET http://localhost:8081/admin/models/{modelId}/status`
- REST endpoint: `POST http://localhost:8081/admin/models/{modelId}/rebuild-and-status`
- REST endpoint: `GET http://localhost:8081/admin/models/{modelId}/window?limit=25`
- REST endpoint: `GET http://localhost:8081/admin/overview?limit=25`
- REST endpoint: `GET http://localhost:8081/admin/models/{modelId}/integrity`
- REST endpoint: `DELETE http://localhost:8081/admin/models/{modelId}`
- Static dashboard: `http://localhost:8081/server-window.html`
- Inbound messages: `Join`, `SubmitOps`, `AcquireLock`, `ReleaseLock`, `Presence`
- Outbound messages: `CheckoutSnapshot`, `CheckoutDelta`, `OpsAccepted`, `OpsBroadcast`, `LockEvent`,
  `PresenceBroadcast`, `Error`
- Service interfaces: `ValidationService`, `RevisionService`, `LockService`, `Neo4jRepository`, `KafkaPublisher`,
  `KafkaConsumer`, `SessionRegistry`, `IdempotencyService`
- In-memory core implementations plus concrete Kafka/Neo4j adapters for local development

## Run

```bash
cd server
mvn quarkus:dev
```

## Local integration tests (Kafka + Neo4j)

These tests are optional and disabled by default. They run against local services from `../docker-compose.yml`.

1. Start infrastructure:

```bash
docker compose up -d
docker compose exec -T neo4j cypher-shell -u "${NEO4J_USER:-neo4j}" -p "${NEO4J_PASSWORD:-devpassword}" < neo4j/schema.cypher
```

2. Run tests with flag:

```bash
cd server
RUN_LOCAL_INFRA_IT=true mvn -q test
```

3. Run websocket end-to-end test (two sessions, Kafka fan-out):

```bash
cd server
RUN_LOCAL_INFRA_IT=true RUN_WS_E2E_IT=true mvn -q test -Dtest=WebSocketEndToEndIT
```

Test class:

- `server/src/test/java/io/archi/collab/service/impl/LocalInfraIntegrationTest.java`
- `server/src/test/java/io/archi/collab/endpoint/WebSocketEndToEndIT.java`

## Example client message envelope

```json
{
  "type": "SubmitOps",
  "payload": {
    "baseRevision": 0,
    "opBatchId": "11111111-1111-1111-1111-111111111111",
    "actor": {
      "userId": "u1",
      "sessionId": "s1"
    },
    "ops": [
      {
        "type": "UpdateViewObjectOpaque",
        "viewId": "view:v1",
        "viewObjectId": "vo:o1",
        "notationJson": {},
        "causal": {
          "clientId": "s1",
          "lamport": 1234,
          "opId": "11111111-1111-1111-1111-111111111111:0"
        }
      }
    ]
  }
}
```

`opBatchId` idempotency is model-scoped in persistence and replay handling, i.e. keyed by `(modelId, opBatchId)`.

Maintainer invariants:
- materialized entity identity is `(modelId, id)`
- commit/idempotency identity is `(modelId, opBatchId)`
- notation field validation and persisted field clocks must flow through `NotationMetadata`
- repository write paths must fail fast on Neo4j persistence errors
- when `app.authz.enabled=true`, Quarkus acts as the PEP and admin endpoints require the configured admin role
- when model ACLs are configured, per-model read/write/admin decisions come from the ACL, not just global reader/writer roles

## Current behavior notes

- Models must be registered through `POST /admin/models/{modelId}` before clients can use them.
- `Join`, `SubmitOps`, `AcquireLock`, `ReleaseLock`, and `Presence` reject unknown `modelId` values with `MODEL_NOT_FOUND`.
- Models use a linear revision timeline with immutable named tags captured from the current `HEAD` snapshot.
- `Join` and `GET /models/{modelId}/snapshot` accept an optional reference (`HEAD` by default, or a tag name).
- Tagged references are read-only; writes and lock/presence traffic are only allowed on `HEAD`.
- Tag deletion is disabled by default. Re-enable it only with `app.tags.allow-delete=true` if you explicitly want mutable tag administration.
- Prefer descriptive tag names that communicate intent, for example `v1.2`, `milestone-3`, or `approved-2026-03-08`.
- `GET /admin/models/{modelId}/export` returns a package containing model metadata, op-log replay history, current snapshot, and tags.
- `POST /admin/models/import?overwrite=true|false` imports that package. Existing models are rejected unless `overwrite=true`, and overwrite is refused while the model has active sessions.
- Export/import is model-scoped and preserves tags as part of the same linear timeline package.
- Model ACLs are managed via `GET/PUT /admin/models/{modelId}/acl`.
- Models created through the admin API seed the creating user as the initial model admin/writer/reader.
- Authorization identity modes:
    - `app.authz.enabled=false` by default
    - `app.identity.mode=bootstrap` by default
    - admin role name defaults to `admin`
    - reader role defaults to `model_reader`
    - writer role defaults to `model_writer`
    - `bootstrap` mode:
        - REST identity comes from `X-Collab-User` and `X-Collab-Roles`
        - websocket identity comes from query parameters `user` and `roles`
        - intended for local/dev and simple standalone deployment
    - `proxy` mode:
        - REST identity comes from trusted forwarded headers
        - websocket identity comes from trusted forwarded headers captured during the handshake
        - header names default to `X-Forwarded-User` and `X-Forwarded-Roles`
        - override with `app.identity.proxy.user-header` and `app.identity.proxy.roles-header`
    - `oidc` mode:
        - REST identity comes from the authenticated Quarkus `SecurityContext`
        - websocket identity comes from the authenticated websocket upgrade principal and role checks captured during the handshake
        - use this when Quarkus itself is running with OIDC/JWT or another auth mechanism that establishes a principal and roles
        - no reverse proxy is required in this mode
    - catalog-wide admin actions (`/admin/models`, create, import, overview) still require the global admin role
    - model-scoped admin actions may be performed by a user listed in that model's ACL

- `SubmitOps` runs the pipeline skeleton:
    - validate
    - lock check for opaque notation updates
    - assign revision range
    - append/apply/update head via repository abstraction
    - publish via Kafka abstraction
    - broadcast acceptance (`OpsAccepted`)
- Neo4j implementation writes commit/op log and applies core materialized state operations.
- Rebuild API clears materialized nodes (elements/relationships/views subtree) and replays op-log commits.
- If an op omits `causal`, the server fills it (`clientId`, `lamport`, `opId`) before persisting/broadcasting.
- `CreateViewObject` and `UpdateViewObjectOpaque` now use LWW merge for geometry fields
  (`x`, `y`, `width`, `height`) using tuple `(lamport, clientId)`.
- Kafka publisher emits JSON payloads to:
    - `archi.model.<modelId>.ops`
    - `archi.model.<modelId>.locks`
    - `archi.model.<modelId>.presence`
- Kafka consumer subscribes to `archi.model.*.ops` and emits websocket `OpsBroadcast`.
- Kafka consumer also subscribes to `locks` and `presence` topics and emits websocket `LockEvent` / `PresenceBroadcast`.

## Snapshot / Rebuild API usage

Get canonical snapshot from Neo4j materialized state:

```bash
curl -s "http://localhost:8081/models/demo/snapshot" | jq .
```

Rebuild materialized state from op-log:

```bash
curl -s -X POST "http://localhost:8081/models/demo/rebuild" | jq .
```

Get aggregated admin status (snapshot counts + consistency):

```bash
curl -s "http://localhost:8081/admin/models/demo/status" | jq .
```

Rebuild and return status in one call:

```bash
curl -s -X POST "http://localhost:8081/admin/models/demo/rebuild-and-status" | jq .
```

Get a model window (status + recent activity + recent op batches):

```bash
curl -s "http://localhost:8081/admin/models/demo/window?limit=25" | jq .
```

Get overview for all known active/recent models:

```bash
curl -s "http://localhost:8081/admin/overview?limit=25" | jq .
```

Dashboard note:

- `server-window.html` now includes style-op telemetry counters and short style history sparklines
  (received/accepted/applied/rejected) based on recent activity.
- `server-window.html` also includes bootstrap auth inputs for `X-Collab-User` and `X-Collab-Roles`, persisted in browser storage for admin/API use when `app.authz.enabled=true` and `app.identity.mode=bootstrap`.
- When `app.identity.mode=proxy`, access the admin UI through the trusted proxy and let the proxy supply forwarded identity headers instead of using the bootstrap inputs.
- When `app.identity.mode=oidc`, the admin UI must be served in the same authenticated Quarkus context; the bootstrap header inputs are not the identity source in that mode.

Get integrity report (missing references/orphans):

```bash
curl -s "http://localhost:8081/admin/models/demo/integrity" | jq .
```

Delete model from server state (development reset):

```bash
curl -s -X DELETE "http://localhost:8081/admin/models/demo" | jq .
```

Force delete even if active sessions exist:

```bash
curl -s -X DELETE "http://localhost:8081/admin/models/demo?force=true" | jq .
```

## Next implementation step

Add integration tests against local Docker Kafka+Neo4j to validate submit -> persist -> publish -> consume -> websocket
fan-out end-to-end.
