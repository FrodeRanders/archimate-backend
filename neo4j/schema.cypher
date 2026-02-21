/*
  Neo4j schema for Archi Cooperative Modelling (materialized state + op-log)
*/

CREATE CONSTRAINT model_id IF NOT EXISTS
FOR (m:Model) REQUIRE m.modelId IS UNIQUE;

CREATE CONSTRAINT element_id IF NOT EXISTS
FOR (e:Element) REQUIRE e.id IS UNIQUE;

CREATE CONSTRAINT rel_id IF NOT EXISTS
FOR (r:Relationship) REQUIRE r.id IS UNIQUE;

CREATE CONSTRAINT view_id IF NOT EXISTS
FOR (v:View) REQUIRE v.id IS UNIQUE;

CREATE CONSTRAINT vo_id IF NOT EXISTS
FOR (o:ViewObject) REQUIRE o.id IS UNIQUE;

CREATE CONSTRAINT conn_id IF NOT EXISTS
FOR (c:Connection) REQUIRE c.id IS UNIQUE;

CREATE CONSTRAINT commit_batch IF NOT EXISTS
FOR (c:Commit) REQUIRE c.opBatchId IS UNIQUE;

CREATE INDEX commit_model_rev IF NOT EXISTS
FOR (c:Commit) ON (c.modelId, c.revisionFrom, c.revisionTo);

/*
Suggested materialized relationships:
(:Model)-[:HAS_ELEMENT]->(:Element)
(:Model)-[:HAS_REL]->(:Relationship)
(:Relationship)-[:SOURCE]->(:Element)
(:Relationship)-[:TARGET]->(:Element)
(:Model)-[:HAS_VIEW]->(:View)
(:View)-[:CONTAINS]->(:ViewObject)
(:ViewObject)-[:REPRESENTS]->(:Element)
(:View)-[:CONTAINS]->(:Connection)
(:Connection)-[:REPRESENTS]->(:Relationship)
(:Connection)-[:FROM]->(:ViewObject)
(:Connection)-[:TO]->(:ViewObject)

Op-log:
(:Commit {modelId, revisionFrom, revisionTo, opBatchId, userId, sessionId, ts})
(:Commit)-[:HAS_OP]->(:Op {seq, type, targetId, payloadJson})
(:Commit)-[:NEXT]->(:Commit)
*/
