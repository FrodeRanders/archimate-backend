# Local Development Setup (Java + Neo4j + Kafka)

This setup runs Neo4j and Kafka locally using Docker Compose.

## Prerequisites
- Docker Desktop (or Docker Engine + Compose plugin)
- Java 21+ (recommended for Quarkus)

## 1) Configure environment
1. Copy `.env.example` to `.env`.
2. Change `NEO4J_PASSWORD` if needed.

```bash
cp .env.example .env
```

## 2) Start containers
```bash
docker compose up -d
```

Services:
- Neo4j Browser: `http://localhost:7474`
- Neo4j Bolt: `bolt://localhost:7687`
- Kafka broker: `localhost:9092`
- Kafka UI: `http://localhost:8080`

## 3) Apply Neo4j constraints/indexes
Run the schema file in the Neo4j container:

```bash
docker compose exec -T neo4j cypher-shell -u "${NEO4J_USER}" -p "${NEO4J_PASSWORD}" < neo4j/schema.cypher
```

## 4) Create model topics (optional for MVP)
Your naming convention is `archi.model.<modelId>.<kind>`.
Example for model `demo`:

```bash
docker compose exec kafka rpk topic create archi.model.demo.ops --brokers localhost:9092
docker compose exec kafka rpk topic create archi.model.demo.locks --brokers localhost:9092
docker compose exec kafka rpk topic create archi.model.demo.presence --brokers localhost:9092
```

## 5) Java/Quarkus connection defaults
Use these in your app config (`application.properties` or env vars):

```properties
# Neo4j
quarkus.neo4j.uri=bolt://localhost:7687
quarkus.neo4j.authentication.username=${NEO4J_USER:neo4j}
quarkus.neo4j.authentication.password=${NEO4J_PASSWORD:devpassword}

# Kafka
kafka.bootstrap.servers=localhost:9092
```

## 6) Helpful checks
List topics:

```bash
docker compose exec kafka rpk topic list --brokers localhost:9092
```

Follow container logs:

```bash
docker compose logs -f neo4j kafka kafka-ui
```

Stop everything:

```bash
docker compose down
```

Stop and remove volumes (clean slate):

```bash
docker compose down -v
```
