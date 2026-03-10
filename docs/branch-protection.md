# Repository Validation

This repository no longer ships GitHub Actions workflow definitions. Validation is run locally through the checked-in scripts.

## Supported entrypoints
- `./scripts/validate-local.sh fast`
- `./scripts/validate-local.sh gate`
- `./scripts/validate-local.sh infra`
- `./scripts/validate-local.sh ws`

## Recommended release gate
1. Run `./scripts/validate-local.sh gate` before routine pushes.
2. Run `./scripts/validate-local.sh infra` before changes that touch persistence, merge semantics, export/import, or websocket flows.
3. Run `./scripts/validate-local.sh ws` before releases or after touching auth/session/message handling.

## Notes
- `infra` and `ws` require the local Docker-backed Kafka/Neo4j stack.
- `fast` is useful as a quick structural regression check, but it is not a substitute for the full gate.
