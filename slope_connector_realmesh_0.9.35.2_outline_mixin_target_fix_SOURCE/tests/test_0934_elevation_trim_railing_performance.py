#!/usr/bin/env python3
from pathlib import Path
import math

ROOT=Path(__file__).parents[1]
smooth=(ROOT/'src/main/java/com/slopeconnector/hotfix/SmoothElevationArcPath.java').read_text()
mixin=(ROOT/'src/main/java/com/slopeconnector/elevation/mixin/ArcRibbonElevationContextMixin.java').read_text()
trim=(ROOT/'src/main/java/com/slopeconnector/hotfix/FinalModelArcTrim.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
model=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()
finder=(ROOT/'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
fabric=(ROOT/'src/main/resources/fabric.mod.json').read_text()

# 1) Elevation path remains a genuine smooth ramp, now with explicit level landings.
for token in ('double landing =', 'transitionStart = landing', 'transitionEnd = run - landing',
              '6.0 * u5 - 15.0 * u4 + 10.0 * u3',
              '30.0 * u2 * (u - 1.0) * (u - 1.0) / transitionRun'):
    assert token in smooth, token
assert 'ArcPath.twoPoint' not in smooth

# The override is limited to two-point UP/DOWN + different height and connects both endpoints on
# horizontal side faces.  It does not touch planar/XZ arc math.
assert '@Mixin(value = ArcRibbonGenerator.class' in mixin
assert 'method = "twoPointContext"' in mixin
assert 'face != Direction.UP && face != Direction.DOWN' in mixin
assert 'startBlock.getY() == endBlock.getY()' in mixin
assert 'horizontalDelta' in mixin and 'runDirection' in mixin
assert 'SmoothElevationArcPath.buildObject(run, rise)' in mixin
assert 'startCenter.add(runDirection.multiply(startInset))' in mixin
assert 'BuildContext' in mixin and 'ResultOrContext' in mixin
assert 'slopeconnector_elevation.mixins.json' in fabric

# 2) Final auto trim indexes ACTUAL final 3-D prisms.  This is what makes upper/lower/left/right
# and diagonal slope cutters all follow the exact volume rather than a planar reconstructed frame.
assert 'indexFinalPrismCutters(component)' in trim
assert 'for (ArcRibbonBlockEntity.Prism prism : member.getPrisms())' in trim
assert 'if (ArcPrismTags.isMetadata(prism)) continue;' in trim
assert 'world[vertex * 3 + 1] = local[vertex * 3 + 1] + holder.getY()' in trim
assert 'indexCutter(result, cutter)' in trim
assert 'state.getOutlineShape' in trim and 'state.getCollisionShape' in trim
assert 'ConvexGeometry.subtract' in trim
assert 'visibleRemainderTriangles' in trim
assert 'collisionPolys.add(ConvexGeometry.translated(inside, toLocal))' not in trim

# 3) Railing endpoint caps: custom arc side plus a real ordinary same-family neighbour both enter
# the cap-cull set.  Inset modded caps are handled too, not just exact x/z=0/1 planes.
assert 'connectedCapDirections(entity, arcFacing)' in endpoint
assert 'ConnectionStateHelper.representedNeighbor' in endpoint
assert 'ConnectionStateHelper.sameFamily(captured, neighbor)' in endpoint
assert 'directions.add(direction)' in endpoint
assert 'Math.abs(average - target) <= 0.126' in endpoint
assert 'quad.getFace() == direction' in endpoint

# 4) Dense widened/thickened arc performance: both captured model and white template skip far owner
# strips, captured quads are decoded/clipped once, and cross-section repetition coarsens curvature
# slicing rather than multiplying 1/8 slices by every width*thickness tile.
assert 'OWNER_RENDER_RADIUS = 48.0' in model
assert 'OWNER_RENDER_RADIUS = 48.0' in template
assert 'ownersByChunk' in model and 'nearbyEntries' in model
assert 'ownersByChunk' in template and 'nearbyEntries' in template
assert 'squaredDistanceTo(viewer) > OWNER_RENDER_RADIUS_SQ' in model
assert 'squaredDistanceTo(viewer) > OWNER_RENDER_RADIUS_SQ' in template
assert 'prepareQuads(quads, sourceAxis, sourceReverse)' in model
assert 'List<PreparedQuad> preparedQuads' in model
assert 'crossSectionTiles >= 4' in model
assert 'base = Math.max(base, 0.25)' in model

# Z post-processing search expansion from 0.9.33 remains in place.
assert 'DISCOVERY_RADIUS_Z = 6' in finder
assert 'ENDPOINT_SEARCH_RADIUS_Z = 8' in finder

print('0.9.34 smooth elevation / 3D trim / railing endpoint / dense performance checks passed')
