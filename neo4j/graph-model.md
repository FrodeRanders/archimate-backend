# Neo4j graph model (suggested)

## Materialized state (current)
- (:Model {modelId, headRevision})
- (:Element {modelId, id, archimateType, name, documentation, propsJson?})
- (:Relationship {modelId, id, archimateType, name, documentation, propsJson?})
- (:View {modelId, id, name, notationJson})
- (:ViewObject {modelId, id, notationJson})
- (:Connection {modelId, id, notationJson})

Relationships
- (Model)-[:HAS_ELEMENT]->(Element)
- (Model)-[:HAS_REL]->(Relationship)
- (Relationship)-[:SOURCE]->(Element)
- (Relationship)-[:TARGET]->(Element)
- (Model)-[:HAS_VIEW]->(View)
- (View)-[:CONTAINS]->(ViewObject)
- (ViewObject)-[:REPRESENTS]->(Element)
- (View)-[:CONTAINS]->(Connection)
- (Connection)-[:REPRESENTS]->(Relationship)
- (Connection)-[:FROM]->(ViewObject)
- (Connection)-[:TO]->(ViewObject)

## Op-log
- (:Commit {modelId, revisionFrom, revisionTo, opBatchId, userId, sessionId, ts})  // unique by (modelId, opBatchId)
- (:Op {seq, type, targetId, payloadJson})

Relationships
- (Commit)-[:HAS_OP]->(Op)
- (Commit)-[:NEXT]->(Commit)  // linear history MVP
