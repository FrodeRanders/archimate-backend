# Collaboration Centralization Plan

## Goal
Make the collaboration server the authoritative source of truth for models, with local files acting as cache/recovery artifacts only.

## Current State
- Server persists op-log and materialized model state in Neo4j.
- Clients synchronize creates/updates/deletes and view placement/movement over WebSocket/Kafka.
- Local `.archimate` files still exist in Archi and can appear as primary working artifacts.

## Plan Ahead
1. Server-backed bootstrap and authoritative hydration
- On connect, treat session as server-backed.
- Always request server snapshot bootstrap (`Join` without `lastSeenRevision`) to hydrate the in-memory model from central state.
- Keep local file as cache/projection only.

2. Cache-only local persistence policy
- Persist server-backed models to deterministic cache path:
  - `~/Archi/collab-cache/<modelId>.archimate`
- Store cache metadata:
  - `modelId`, `lastKnownRevision`, `serverBacked`, timestamp
- Autosave cache during sync traffic and force-save on detach/disconnect.
- Objective: minimize/avoid save prompts for collaboration-backed sessions on close.

3. Cache validity and rejoin policy
- On reconnect, compare cache metadata revision against server head.
- If stale, apply server snapshot/delta and refresh cache.
- If inconsistent/unknown, discard cache projection and rebuild from server snapshot.

4. Expand CRDT coverage
- Keep deterministic merge clocks and per-field policy for conflict-prone fields:
  - geometry, style, selected semantic fields.
- Preserve idempotent causal metadata and deterministic replay.

5. Offline/outbox and rebase
- Queue local ops while offline.
- Rebase queued ops against server head on reconnect using merge policy.

6. Operational hardening
- Admin observability for hydration/cache/recovery outcomes.
- Integrity checks and auto-repair tooling for dangling refs.

## Implemented in this change (Steps 1 and 2)
- Client session manager now defaults to server-backed mode.
- Join revision resolution now requests cold-start snapshot when server-backed.
- Added cache autosave policy in client plugin:
  - autosave on `CheckoutSnapshot`, `CheckoutDelta`, `OpsBroadcast`, `OpsAccepted` (debounced)
  - force-save on model attach, detach, and disconnect
  - deterministic cache path + `.meta.properties` metadata
- Connect flow explicitly marks session as server-backed.

## Implemented in this change (Step 3)
- Added cache rejoin decision logic from metadata:
  - if metadata is valid: send `Join` with `lastSeenRevision` from cache (delta path)
  - if metadata is missing/invalid/inconsistent: discard cache projection and send `Join` without revision (cold snapshot path)
- Added reconnect revision comparison:
  - first server revision hint is compared with cache revision used at join
  - if server revision is behind cache revision, client requests a cold snapshot rebuild (`Join` without revision)

## Implemented in this change (Step 4)
- Extended deterministic LWW merge by causal clock from geometry-only to broader notation fields.
- `UpdateViewObjectOpaque` now performs per-field LWW for:
  - geometry (`x`,`y`,`width`,`height`)
  - style/visual fields (`fillColor`,`lineColor`,`font`,`fontColor`,`lineWidth`,`lineStyle`,`alpha`,`lineAlpha`,`gradient`,`iconColor`, etc.)
  - selected semantic notation fields (`name`,`documentation`)
- `UpdateConnectionOpaque` now performs per-field LWW for:
  - style/visual fields (`lineColor`,`font`,`fontColor`,`lineWidth`, alignment/position)
  - selected semantic notation fields (`name`,`documentation`)
  - bendpoint array as a single LWW field
- Added per-object notation clocks for both view objects and connections, seeded on create and refreshed on winning updates.

## Implemented in this change (Step 5)
- Added client-side offline outbox for `SubmitOps`:
  - if socket is unavailable, ops are queued (bounded queue) instead of dropped.
  - if live send fails, op is moved into outbox for retry.
- Added reconnect replay with rebase:
  - queued ops are replayed for the active model after revision hints are available.
  - each replayed op has `baseRevision` rebased to current known server revision before send.
- Added websocket close handling to preserve collaboration model context while offline.
- Updated EMF capture gating so edits continue to be captured into outbox when model context exists but socket is down.
- Added durable outbox persistence:
  - queued ops are persisted per model under collaboration cache directory.
  - persisted queue is reloaded on reconnect before replay.
  - durable queue is pruned/rewritten as entries are replayed or dropped.
