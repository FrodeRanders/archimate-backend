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
