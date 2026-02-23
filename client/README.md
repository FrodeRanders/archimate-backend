# Collaboration Client Workspace

This directory now supports a standalone Tycho build for the collaboration plugin.

## What this build uses

- `client/com.archimatetool.collab` as the plugin source.
- Eclipse 2024-06 p2 repository for Eclipse/EMF bundles.
- A local bundle pool in `client/target-platform/plugins` for Archi bundles required by the plugin:
  - `com.archimatetool.model`
  - `com.archimatetool.editor`

The local bundle pool is prepared by copying the required Archi bundles into `client/target-platform/plugins`.
The local bundle pool can be prepared from either:
- an Archi source checkout (`ARCHI_DIR`, default `../archi`), or
- an installed Archi app plugins directory (`ARCHI_APP_PATH` / `ARCHI_APP_PLUGINS_DIR`).

## Commands

- Prepare target bundles:
  - `./scripts/prepare-client-target-platform.sh`
- Build standalone plugin:
  - `./scripts/build-client-standalone.sh`

## Notes

- This lets client work live with server code in one repo.
- It does not require syncing plugin sources to another repo.
- If required bundles change in `MANIFEST.MF`, update `scripts/prepare-client-target-platform.sh`.
- Building/running Tycho client builds may require escalated permissions in this environment due to `~/.m2` lock behavior.

## Startup Pull From Server (Optional)

You can opt in to opening a collaboration model from the central server when Archi starts, without first opening a local `.archimate` file.

You can also open directly from server at runtime using:
- `Tools -> Open Collaboration Model from Server...`

Or convert the currently active local model to server-backed collaboration:
- `Tools -> Switch Active Model to Server-Backed...`

Both runtime dialogs load model options from server admin catalog (`/admin/models`) and only allow selecting an existing model.
Create/rename model IDs in server admin UI first (`/admin`).

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
