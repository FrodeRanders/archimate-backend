# Collaboration Sanity Checklist

Use this after a fresh start (new model file, empty workspaces).

## 1) Start services and clients

1. Start infra + server:
   - `./scripts/dev-up.sh background`
2. Tail logs:
   - `./scripts/tail-collab-logs.sh`
3. Launch two Archi instances (WS1 and WS2) and connect both to the same model.

## 2) Run the core scenario

1. In WS1 create two Business elements.
2. Verify both appear in WS2.
3. Rename one element in WS1.
4. Verify rename appears in WS2.
5. Drag one element into a View in WS1.
6. Verify it appears in WS2.
7. Move that view object in WS1.
8. Verify movement appears in WS2.
9. Create a relationship between the two elements in WS1.
10. Verify relationship appears in WS2.
11. Delete one endpoint element in WS1.
12. Verify relationship disappears in both WS1 and WS2.
13. Save model in WS1 and WS2.
14. Close WS1 and WS2.

## 3) Check logs for integrity issues

Run:

`./scripts/check-collab-sanity.sh --combined-log ./log.log`

or directly against live logs:

`./scripts/check-collab-sanity.sh --server-log ./.run/collab-server.log --ws1-log "$HOME/Archi/workspace-1/.metadata/.log" --ws2-log "$HOME/Archi/workspace-2/.metadata/.log"`

## 4) Pass criteria

1. Element create/rename/view placement/move syncs WS1 <-> WS2.
2. Relationship create/delete syncs without dangling endpoint errors.
3. No critical findings in `check-collab-sanity.sh` output.

