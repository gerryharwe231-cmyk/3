#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).parents[1]
trim=(ROOT/'src/main/java/com/slopeconnector/hotfix/FinalModelArcTrim.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
context=(ROOT/'src/main/java/com/slopeconnector/model/ArcBuildGroupContext.java').read_text()
distance=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRenderDistanceMixin.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()
model=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
finder=(ROOT/'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
dimension=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()

# Final auto trim must include every widened/thickened endpoint tile, not only the first curved segment.
assert 'appendEndpointCutters(world, component.startModelBlock(), cuttersByCell)' in trim
assert 'appendEndpointCutters(world, component.endModelBlock(), cuttersByCell)' in trim
assert 'model.getEndpointLateralSpan()' in trim and 'model.getEndpointVerticalSpan()' in trim
assert 'widthTiles' in trim and 'radialTiles' in trim
assert 'indexCutter(result,new ArcAutoTrim.WorldPrism(xyz))' in trim
# Final trim collision union still includes the same cutter volume, so trimmed ordinary collision
# cannot remain overlapped with the expanded endpoint tile.
assert 'collisionPolys.add(ConvexGeometry.translated(inside, toLocal))' not in trim
assert 'visibleRemainderTriangles' in trim

# Connected endpoint BakedModels remove caps both toward the custom arc and toward real ordinary
# same-family neighbours. This remains geometry/state-driven, never camera-driven.
assert 'ConnectionStateHelper.isSupported(entity.getCapturedState())' in endpoint
assert 'connectedCapDirections(entity, arcFacing)' in endpoint
assert 'ConnectionStateHelper.sameFamily(captured, neighbor)' in endpoint
assert 'shouldCullConnectedCap(quad, baseVertices, connectedCaps)' in endpoint
assert 'current view' not in endpoint.lower()  # no camera-dependent workaround

# Pure-white template groups are consolidated from generation time, just like captured models.
assert 'ArcPrismTags.renderLeader(renderRadius)' in context
assert 'if (ArcPrismTags.groupId(entity) != 0L)' in distance
assert 'if (!ArcPrismTags.isRenderLeader(entity)) return false' in distance
assert 'handle.renderLeader()' in template
assert 'nearbyEntries(handle.byOwner(), handle.ownersByChunk(), viewer)' in template
# Cache validity is O(1) on the current leader, not an every-frame scan across every member BE.
assert 'for (Map.Entry<BlockPos,Integer> entry : memberRevisions.entrySet())' not in template
assert 'for (Map.Entry<BlockPos,Integer> entry : memberRevisions.entrySet())' not in model

# Z receives an explicit wider post-processing connection/discovery radius.  Original 0.9.23 axis
# mapping remains untouched; this is only the model-arc group/endpoint discovery range.
assert 'DISCOVERY_RADIUS_Z = 6' in finder
assert 'ENDPOINT_SEARCH_RADIUS_Z = 8' in finder
assert 'dz=-DISCOVERY_RADIUS_Z' in finder or 'dz = -DISCOVERY_RADIUS_Z' in finder
assert 'dz=-ENDPOINT_SEARCH_RADIUS_Z' in finder or 'dz = -ENDPOINT_SEARCH_RADIUS_Z' in finder
# Keep the previously requested guard: UP/DOWN with same-height endpoints is still rejected.
assert 'face == Direction.UP || face == Direction.DOWN' in dimension
assert 'startBlock.getY() == endBlock.getY()' in dimension

print('0.9.33 endpoint trim / railing cap / Z range / dense-render performance checks passed')
