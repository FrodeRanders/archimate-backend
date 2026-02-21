# Notation mapping (Archi EMF -> canonical notationJson payload)

This document guides implementation of a client-side NotationSerializer/Deserializer.
The server and Neo4j treat notationJson as opaque blobs.

## Identity
Use Archi EMF `IIdentifier.getId()` as the stable id (MVP):
- elementId: "elem:<emfId>"
- relationshipId: "rel:<emfId>"
- viewId: "view:<emfId>"
- viewObjectId: "vo:<emfId>"
- connectionId: "conn:<emfId>"

## View (IDiagramModel / IArchimateDiagramModel)
- name: INameable.getName() -> (semantic `CreateView/UpdateView` field)
- payload.router.type: IDiagramModel.getConnectionRouterType()
- payload.viewpoint: IArchimateDiagramModel.getViewpoint()
- payload.doc: IDocumentable.getDocumentation()
- payload.props: flatten IProperties.getProperties() list -> {key:value} map
- payload.extras: anything else you want to preserve

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
- payload.archi.imageSource: obj.getImageSource()
- payload.archi.useProfileImage: obj.useProfileImage()
- payload.label.textPosition: (if implements ITextPosition) getTextPosition()
- payload.image.path: (if implements IDiagramModelImageProvider) getImagePath()
- payload.image.position: (if implements IIconic) getImagePosition()
- payload.grouping.childrenIds: (if implements IDiagramModelContainer) child ids

Group (IDiagramModelGroup):
- treat as ViewObject with payload.kind="Group"
- add border type (if implements IBorderType)

Note (IDiagramModelNote):
- treat as ViewObject with payload.kind="Note"
- add text content (ITextContent.getContent)
- legend flags/options (isLegend/getLegendOptions) go in extras unless normalized later

## Connection (IDiagramModelConnection)
- payload.endpoints.sourceViewObjectId: "vo:<conn.getSource().getId()>"
- payload.endpoints.targetViewObjectId: "vo:<conn.getTarget().getId()>"
- payload.routing.bendpoints[]: for each IDiagramModelBendpoint:
  {startX,startY,endX,endY}
- payload.style.line.typeBits: conn.getType() (bitmask; keep opaque)
- payload.style.text.position: conn.getTextPosition()
- payload.style.text.nameVisible: conn.isNameVisible()
- plus ILineObject/IFontAttribute/ITextAlignment/IProperties/IDocumentable similar to ViewObject

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
