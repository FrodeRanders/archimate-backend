# Kafka topics and partitioning (MVP)

## Topics (per model)
- archi.model.<modelId>.ops
- archi.model.<modelId>.locks
- archi.model.<modelId>.presence

## Partitioning
- ops: 1 partition per model to preserve total order
- locks: 1 partition per model (lease/order simplicity)
- presence: 1 partition per model or keyed by sessionId

## Keys
- ops: constant key = <modelId>
- locks: constant key = <modelId>
- presence: key = <sessionId> (or <userId>)

## Retention
- ops: long retention if you rely on Kafka for rebuild, otherwise moderate (Neo4j op-log is the durable log)
- locks: short retention (minutes)
- presence: very short retention (minutes)
