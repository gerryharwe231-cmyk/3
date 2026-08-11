#!/usr/bin/env python3
from pathlib import Path
import math

ROOT=Path(__file__).parents[1]

panel=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
client_dim=(ROOT/'src/main/java/com/slopeconnector/surface/dimensions/ArcDimensionClientState.java').read_text()
common=(ROOT/'src/main/java/com/slopeconnector/surface/SurfaceRefineMod.java').read_text()
model_renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
endpoint_renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
endpoint_block=(ROOT/'src/main/java/com/slopeconnector/model/ModelEndpointBlock.java').read_text()
state_resolver=(ROOT/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
wand=(ROOT/'src/main/java/com/slopeconnector/model/ModelRenderWandItem.java').read_text()
proxy=(ROOT/'src/main/java/com/slopeconnector/model/ArcCollisionProxyBuilder.java').read_text()
dimension_mixin=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()
binder=(ROOT/'src/main/java/com/slopeconnector/model/ArcEndpointMetadataBinder.java').read_text()
stations=(ROOT/'src/main/java/com/slopeconnector/model/ArcStationFrames.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()

# 1) Requested UI semantics: the original width control is now called up/down thickness; the old
# secondary thickness control is now called side width.  Internal commands stay compatible.
assert '上下 -' in panel and '上下 +' in panel
assert '上下厚度' in panel
assert '侧面 -' in panel and '侧面 +' in panel
assert '侧面宽度' in panel and '侧面宽度：' in client_dim
assert '弧带上下厚度：' in common and '弧带侧面宽度：' in common

# 2) Width/thickness >1 is repeated as one-block source model tiles in BOTH the curved middle and
# expanded endpoint.  Never scale a single captured texture/model across the total span.
for token in ('lateralTiles', 'verticalTiles', 'lateralTile', 'verticalTile',
              'double lateralCell = frame.lateralSpan / lateralTiles',
              'double verticalCell = frame.verticalSpan / verticalTiles'):
    assert token in model_renderer, token
assert 'isBoundaryFace(original, Axis.LATERAL' in model_renderer
assert 'isBoundaryFace(original, Axis.VERTICAL' in model_renderer
assert 'prepared.lateralMin()' in model_renderer and 'prepared.lateralMax()' in model_renderer
assert 'prepared.verticalMin()' in model_renderer and 'prepared.verticalMax()' in model_renderer
for token in ('widthTiles', 'radialTiles', 'isBoundaryFace(baseVertices, widthAxis',
              'isBoundaryFace(baseVertices, radialAxis'):
    assert token in endpoint_renderer, token

# Integer spans must produce exactly 1-block cells: checker/brick UVs therefore repeat per strip.
for span in (2.0,3.0,4.0,8.0):
    tiles=round(span); assert abs(span/tiles-1.0)<1e-12

# 3) Collision is not limited to the centre holder: actual widened/thickened prism volume is
# rasterized into invisible collision-only ArcRibbon proxy holders, including expanded endpoints.
for token in ('ArcPrismTags.collisionProxy', 'addEndpointOccupancy', 'addOccupancy',
              'clearOldProxies', 'proxyOnly'):
    assert token in proxy, token
assert 'ArcCollisionProxyBuilder.rebuild(world, startBlock)' in dimension_mixin
assert 'repeatedEndpointShape' in endpoint_block
assert 'source.offset(offset.x, offset.y, offset.z)' in endpoint_block

# 4) Direct endpoint -> curve uses an endpoint-aligned *station*, not per-vertex miter hacks.  The
# same station goes to white template, captured model renderer and endpoint metadata.
assert 'public static Station alignEndpoint' in stations
assert 'axis.dotProduct(station.tangent()) < 0.0' in stations
assert 'ArcStationFrames.alignEndpoint' in template
assert 'ArcStationFrames.alignEndpoint' in model_renderer
assert 'ArcStationFrames.alignEndpoint' in binder
assert 'miterEndpoint' not in model_renderer
assert 'miterToEndpoint' not in template

# Simple fixture: a 45deg local frame projected onto an east/west endpoint plane gets x=constant for
# every cross-section point; there can be neither an outer wedge nor an inner overlap at the face.
normal=(1.0,0.0,0.0)
raw_width=(0.5,0.0,math.sqrt(.75))
raw_radial=(0.5,math.sqrt(.75),0.0)
def project(v,n):
    d=sum(v[i]*n[i] for i in range(3))
    p=tuple(v[i]-n[i]*d for i in range(3))
    l=math.sqrt(sum(x*x for x in p));return tuple(x/l for x in p)
w=project(raw_width,normal);r=project(raw_radial,normal)
assert abs(w[0])<1e-12 and abs(r[0])<1e-12

# 5) Captured model-state priority.  The wand writes the complete BlockState.  Stairs/slabs still
# win over connected-profile seam handling, but stair SHAPE is normalized to STRAIGHT so a captured
# neighbour-driven corner does not inject a bogus T/arrow footprint into the arc.
assert 'MaterialStateCodec.write(state)' in wand and 'MaterialStateCodec.read' in wand
assert 'canonicalSourceState' in state_resolver
idx_priority=state_resolver.index('// Priority 1: stairs/slabs keep the captured facing')
idx_stair=state_resolver.index('if (isStairOrSlab(normalized)) return normalized', idx_priority)
idx_connected=state_resolver.index('if (ConnectionStateHelper.isSupported(normalized))', idx_stair)
idx_generic=state_resolver.index('// Priority 3: ordinary texture/model continuity', idx_connected)
assert idx_priority < idx_stair < idx_connected < idx_generic
assert 'state.getBlock() instanceof StairsBlock' in state_resolver
assert 'state.getBlock() instanceof SlabBlock' in state_resolver
assert 'StairShape.STRAIGHT' in state_resolver
stair_branch=state_resolver[idx_priority:idx_connected]
assert 'BlockState normalized = canonicalSourceState(captured);' in state_resolver
assert 'isStairOrSlab(normalized)) return normalized' in stair_branch
# Their middle deformation still uses canonical local +X, so the captured baked orientation itself
# remains visible instead of being normalized away by the path axis.
assert 'if (isStairOrSlab(state)) return Direction.Axis.X' in state_resolver
assert 'if (isStairOrSlab(state)) return false' in state_resolver
endpoint_renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
assert 'exactCapturedShape' in endpoint_renderer
assert 'ModelStateResolver.isStairOrSlab(entity.getCapturedState())' in endpoint_renderer
# Railing/connected profiles run their native endpoint-state bridge before generic seam handling.
assert 'ConnectionStateHelper.endpointState(normalized, connectionDirection, world, pos)' in state_resolver

# 6) Endpoint width/thickness metadata is stamped before collision proxies are built, so proxies can
# cover the added endpoint cells too.
assert dimension_mixin.index('ArcEndpointMetadataBinder.bind') < dimension_mixin.index('ArcCollisionProxyBuilder.rebuild')
assert 'setEndpointFrame' in binder

print('0.9.28 dimension naming, tiled models, collision proxies, endpoint seam and model-state priorities passed')
