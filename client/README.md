# Collaboration Client Workspace

This directory now supports a standalone Tycho build for the collaboration plugin.

## What this build uses

- `client/com.archimatetool.collab` as the plugin source.
- Eclipse 2024-06 p2 repository for Eclipse/EMF bundles.
- A local bundle pool in `client/target-platform/plugins` for Archi bundles required by the plugin:
  - `com.archimatetool.model`
  - `com.archimatetool.editor`

The local bundle pool is prepared by copying jars from `../archi`.

## Commands

- Prepare target bundles:
  - `./scripts/prepare-client-target-platform.sh`
- Build standalone plugin:
  - `./scripts/build-client-standalone.sh`

## Notes

- This lets client work live with server code in one repo.
- It still expects an Archi checkout at `../archi` to source `editor` and `model` jars.
- If required bundles change in `MANIFEST.MF`, update `scripts/prepare-client-target-platform.sh`.

## Startup Pull From Server (Optional)

You can opt in to opening a collaboration model from the central server when Archi starts, without first opening a local `.archimate` file.

Configuration can be provided as Java system properties or environment variables:

- Enable:
  - `archi.collab.startup.pull.enabled=true`
  - `ARCHI_COLLAB_STARTUP_PULL_ENABLED=true`
- Required model id:
  - `archi.collab.startup.pull.modelId=<model-id>`
  - `ARCHI_COLLAB_STARTUP_PULL_MODEL_ID=<model-id>`
- Optional websocket base URL (default `ws://localhost:8081`):
  - `archi.collab.startup.pull.wsBaseUrl=<ws-base-url>`
  - `ARCHI_COLLAB_STARTUP_PULL_WS_BASE_URL=<ws-base-url>`
- Optional actor:
  - `archi.collab.startup.pull.userId=<user-id>`
  - `ARCHI_COLLAB_STARTUP_PULL_USER_ID=<user-id>`
  - `archi.collab.startup.pull.sessionId=<session-id>`
  - `ARCHI_COLLAB_STARTUP_PULL_SESSION_ID=<session-id>`
- Optional local display name for the opened model:
  - `archi.collab.startup.pull.modelName=<name>`
  - `ARCHI_COLLAB_STARTUP_PULL_MODEL_NAME=<name>`

Behavior:
- Runs only when enabled.
- Skips startup pull if one or more models are already open.
- Opens an in-memory model, connects to collaboration server, and requests checkout snapshot/delta.
