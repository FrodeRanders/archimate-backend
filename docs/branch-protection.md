# Branch Protection Setup

This guide configures GitHub branch protection so CRDT regressions block merges.

## Prerequisites
- You have repository admin permissions.
- GitHub Actions is enabled for the repository.
- The workflows exist:
  - `.github/workflows/crdt-gate.yml`
  - `.github/workflows/crdt-local-infra.yml`

## First Run (important)
Before adding required checks, trigger one run so GitHub can discover check names:
1. Open the repository on GitHub.
2. Open `Actions`.
3. Run workflow `CRDT Gate` once using `Run workflow`.
4. (Optional) Run `CRDT Local Infra` once.

## Configure Protection Rule
1. Open `Settings` in the repository.
2. Open `Branches`.
3. In `Branch protection rules`, click `Add rule` (or edit existing rule).
4. Set `Branch name pattern` to `main`.
5. Enable `Require a pull request before merging`.
6. Enable `Require status checks to pass before merging`.
7. In required checks, add:
   - `CRDT Gate / crdt-gate`
8. Save the rule.

## Recommended Non-blocking Signal
- Keep this as informational (not required):
  - `CRDT Local Infra / crdt-local-infra`
- Rationale: it is heavier, depends on Docker/Kafka/Neo4j, and is better as nightly/manual signal.

## Optional Hardening
- Enable `Require branches to be up to date before merging`.
- Enable `Require conversation resolution before merging`.
- Enable `Do not allow bypassing the above settings` (if your governance requires strict enforcement).

## Verify
1. Open or update a pull request.
2. Confirm `CRDT Gate / crdt-gate` appears and is required.
3. Confirm merge is blocked when that check fails.
