# Collab Admin Web

This is the Svelte-based admin UI for the collaboration server.

Current status:
- `Overview` is implemented as a real route.
- `Access` is implemented as a real route.
- `Versions` is implemented as a real route.
- `Models` is implemented as a real route.
- `Sessions` is implemented as a real route.
- `Audit` is implemented as a real route.
- `server-window.html` is now only a redirect to `/admin-ui/`.

## Build

From the repo root:

```bash
scripts/build-admin-web.sh
```

That installs frontend dependencies in `admin-web/` and emits the static build into:

- `server/src/main/resources/META-INF/resources/admin-ui`

Quarkus then serves the built app at:

- `/admin-ui/`
