# Canonical notationJson contract (client-side semantics only)

Server/Neo4j treat this as an opaque blob. Only the Archi plugin interprets it.

## Base envelope
{
  "schemaVersion": "1.0",
  "producer": { "app": "Archi", "plugin": "archi-collab", "pluginVersion": "0.1.0" },
  "entity": { "kind": "View|ViewObject|Connection", "id": "view:..|vo:..|conn:..", "viewId": "view:.." },
  "timestamps": { "capturedAt": "RFC3339" },
  "payload": { ... }
}

## View payload (suggested)
payload: {
  "router": { "type": <int> },
  "viewpoint": "<string>",
  "doc": "<string>",
  "props": { "<k>": "<v>", ... },
  "extras": { ... }
}

## ViewObject payload (suggested)
payload: {
  "representsId": "elem:<id>",
  "geometry": { "x": <int>, "y": <int>, "w": <int>, "h": <int>, "rotation": 0 },
  "zOrder": <int>,
  "visibility": { "hidden": false, "locked": false },
  "style": {
    "fill": { "color": "#rrggbb", "opacity": 0.0..1.0, "gradient": <int> },
    "line": { "color": "#rrggbb", "width": <int>, "alpha": 0.0..1.0, "style": <int> },
    "font": { "spec": "<string>", "color": "#rrggbb" },
    "text": { "alignment": <int> }
  },
  "icon": { "visibleState": <int>, "color": "#rrggbb", "deriveLineColor": <bool> },
  "label": { "textPosition": <int> },
  "image": { "path": "<string|null>", "position": <int|null> },
  "grouping": { "parentViewObjectId": null, "childrenIds": [ ... ] },
  "extras": { ... }
}

## Connection payload (suggested)
payload: {
  "representsId": "rel:<id>",
  "endpoints": { "sourceViewObjectId": "vo:<id>", "targetViewObjectId": "vo:<id>" },
  "routing": {
    "mode": "manual",
    "bendpoints": [ { "startX": <int>, "startY": <int>, "endX": <int>, "endY": <int> }, ... ]
  },
  "style": {
    "line": { "color": "#rrggbb", "width": <int>, "typeBits": <int> },
    "font": { "spec": "<string>", "color": "#rrggbb" },
    "text": { "alignment": <int>, "position": <int>, "nameVisible": <bool> }
  },
  "doc": "<string>",
  "props": { "<k>": "<v>", ... },
  "extras": { ... }
}

## Update strategy (MVP)
Use full replacement updates:
- UpdateViewObjectOpaque { viewId, viewObjectId, notationJson }
- UpdateConnectionOpaque { viewId, connectionId, notationJson }
