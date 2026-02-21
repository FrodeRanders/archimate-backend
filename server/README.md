# Collaboration Server Skeleton (Quarkus)

This module implements the MVP scaffold from `codex_prompt.txt`.

## Includes
- WebSocket endpoint: `ws://localhost:8081/models/{modelId}/stream`
- Inbound messages: `Join`, `SubmitOps`, `AcquireLock`, `ReleaseLock`, `Presence`
- Outbound messages: `CheckoutSnapshot`, `CheckoutDelta`, `OpsAccepted`, `OpsBroadcast`, `LockEvent`, `PresenceBroadcast`, `Error`
- Service interfaces: `ValidationService`, `RevisionService`, `LockService`, `Neo4jRepository`, `KafkaPublisher`, `KafkaConsumer`, `SessionRegistry`, `IdempotencyService`
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
    "actor": { "userId": "u1", "sessionId": "s1" },
    "ops": [
      { "type": "UpdateViewObjectOpaque", "viewId": "view:v1", "viewObjectId": "vo:o1", "notationJson": {} }
    ]
  }
}
```

## Current behavior notes
- `SubmitOps` runs the pipeline skeleton:
  - validate
  - lock check for opaque notation updates
  - assign revision range
  - append/apply/update head via repository abstraction
  - publish via Kafka abstraction
  - broadcast acceptance (`OpsAccepted`)
- Neo4j implementation writes commit/op log and applies core materialized state operations.
- Kafka publisher emits JSON payloads to:
  - `archi.model.<modelId>.ops`
  - `archi.model.<modelId>.locks`
  - `archi.model.<modelId>.presence`
- Kafka consumer subscribes to `archi.model.*.ops` and emits websocket `OpsBroadcast`.
- Kafka consumer also subscribes to `locks` and `presence` topics and emits websocket `LockEvent` / `PresenceBroadcast`.

## Next implementation step
Add integration tests against local Docker Kafka+Neo4j to validate submit -> persist -> publish -> consume -> websocket fan-out end-to-end.
