# Notation mapping (Archi EMF -> canonical notationJson payload)

This document guides implementation of the client-side `NotationSerializer`/`NotationDeserializer`.
Status in current CRDT phase:
- notation keys are schema-whitelisted (`schemas/ops.json`, `additionalProperties: false`);
- server/client apply explicit per-field LWW merge for supported notation keys;
- unknown notation keys are rejected (`PRECONDITION_FAILED`), not stored as opaque passthrough.

## Current collection semantics status
Explicitly CRDT-supported collection domain:
- `ConnectionNotationJson.bendpoints[]` is treated as a single LWW field (`bendpoints`) with causal ordering.
- grouped view-object child membership via dedicated collection ops:
  - `AddViewObjectChildMember { parentViewObjectId, childViewObjectId, causal }`
  - `RemoveViewObjectChildMember { parentViewObjectId, childViewObjectId, causal }`

Explicitly unsupported collection domains (must not be emitted by client ops yet):
- arbitrary nested notation bags (`props`, `extras`, unordered maps/lists);
- any add/remove collection intent that requires element-level conflict resolution.

## Identity
Use Archi EMF `IIdentifier.getId()` as the stable id:
- elementId: "elem:<emfId>"
- relationshipId: "rel:<emfId>"
- viewId: "view:<emfId>"
- viewObjectId: "vo:<emfId>"
- connectionId: "conn:<emfId>"

## View (IDiagramModel / IArchimateDiagramModel)
- name: INameable.getName() -> (semantic `CreateView/UpdateView` field)
- documentation: IDocumentable.getDocumentation() -> (semantic `UpdateView.patch.documentation`)
- notationJson for view-level router/viewpoint/extra fields is not supported in current schema.

## ViewObject (IDiagramModelObject)
Geometry:
- payload.geometry.x/y/w/h: obj.getBounds().getX/Y/Width/Height
- rotation: 0 (unless you find a rotation field later)

Style:
- payload.style.fill.color: obj.getFillColor()
- payload.style.fill.opacity: obj.getAlpha() / 255.0
- payload.style.fill.gradient: obj.getGradient()
- payload.style.line.alpha: obj.getLineAlpha()/255.0
- payload.style.line.style: obj.getLineStyle()
- payload.style.line.width/color: (if obj implements ILineObject) getLineWidth/getLineColor
- payload.style.font.spec/color: (if obj implements IFontAttribute) getFont/getFontColor
- payload.style.text.alignment: (if obj implements ITextAlignment) getTextAlignment

Icon:
- payload.icon.visibleState: obj.getIconVisibleState()
- payload.icon.color: obj.getIconColor()
- payload.icon.deriveLineColor: obj.getDeriveElementLineColor()

ArchiMate object (IDiagramModelArchimateObject):
- payload.representsId: "elem:<obj.getArchimateElement().getId()>"
- payload.archi.diagramObjectType: obj.getType()
- payload.label.textPosition: (if implements ITextPosition) getTextPosition()
- payload.image.path: (if implements IDiagramModelImageProvider) getImagePath()
- payload.image.position: (if implements IIconic) getImagePosition()

Group (IDiagramModelGroup):
- treat as ViewObject with payload.kind="Group"
- border/member list fields are not currently in collaboration notation schema.

Note (IDiagramModelNote):
- treat as ViewObject with payload.kind="Note"
- text content/legend options are not currently in collaboration notation schema.

## Connection (IDiagramModelConnection)
- payload.endpoints.sourceViewObjectId: "vo:<conn.getSource().getId()>"
- payload.endpoints.targetViewObjectId: "vo:<conn.getTarget().getId()>"
- payload.routing.bendpoints[]: for each IDiagramModelBendpoint:
  {startX,startY,endX,endY}
- payload.style.line.typeBits: conn.getType() (bitmask; keep opaque)
- payload.style.text.position: conn.getTextPosition()
- payload.style.text.nameVisible: conn.isNameVisible()
- plus ILineObject/IFontAttribute/ITextAlignment/IDocumentable similar to ViewObject

ArchiMate connection (IDiagramModelArchimateConnection):
- payload.representsId: "rel:<conn.getArchimateRelationship().getId()>"

## Update strategy
- Full replacement updates:
  - UpdateViewObjectOpaque { viewId, viewObjectId, notationJson }
  - UpdateConnectionOpaque { viewId, connectionId, notationJson }

## Debounce/coalesce
- Bounds changes and bendpoint changes are noisy:
  - debounce per object/connection (150–250ms recommended)
  - send only last state in window

## Echo suppression
During remote apply, suppress local change capture (ThreadLocal flag or temporary listener disable) to avoid feedback loops.
