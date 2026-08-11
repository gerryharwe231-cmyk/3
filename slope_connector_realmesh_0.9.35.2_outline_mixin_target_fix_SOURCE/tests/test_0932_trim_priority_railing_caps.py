#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).parents[1]
dimension=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()
trim=(ROOT/'src/main/java/com/slopeconnector/hotfix/FinalModelArcTrim.java').read_text()
resolver=(ROOT/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
entity=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlockEntity.java').read_text()
binder=(ROOT/'src/main/java/com/slopeconnector/model/ArcEndpointMetadataBinder.java').read_text()
arc=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()

# Auto-trim is deferred until AFTER the final width/thickness prisms exist.
assert 'slopeconnectorSurface$deferEmbeddedAutoTrim' in dimension
assert 'return !SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE.get() && ArcAutoTrimSettings.enabled();' in dimension
assert 'ArcAutoTrimSettings.set(false)' not in dimension
assert 'FinalModelArcTrim.rebuild(world, startBlock)' in dimension
assert dimension.index('ArcEndpointMetadataBinder.bind') < dimension.index('FinalModelArcTrim.rebuild')
assert dimension.index('FinalModelArcTrim.rebuild') < dimension.index('ArcCollisionProxyBuilder.rebuild')

# Final trim now consumes the ACTUAL final prisms, which is stricter than reconstructing cutters
# from shared stations for a 3-D elevation curve. Stairs/slabs still use their real source shapes.
assert 'indexFinalPrismCutters(component)' in trim
assert 'for (ArcRibbonBlockEntity.Prism prism : member.getPrisms())' in trim
assert 'state.getOutlineShape' in trim and 'state.getCollisionShape' in trim
assert 'shape.getBoundingBoxes()' in trim
assert 'collisionPolys.add(ConvexGeometry.translated(inside, toLocal))' not in trim
assert 'visibleRemainderTriangles' in trim
assert 'VoxelShapeUtil.voxelizePolys(collisionPolys' in trim
assert 'isOrdinaryFull' not in trim

# Priority chain is explicit: stairs/slabs first; connected profiles second; generic seam third.
idx_stair=resolver.index('// Priority 1: stairs/slabs')
idx_connected=resolver.index('// Priority 2: connected profiles')
idx_seam=resolver.index('// Priority 3: ordinary texture/model continuity')
assert idx_stair < idx_connected < idx_seam
assert 'if (isStairOrSlab(normalized)) return normalized;' in resolver
assert 'ConnectionStateHelper.endpointState(normalized, connectionDirection' in resolver
assert 'terminalEnd ? connectionDirection.getOpposite() : connectionDirection' in resolver

# The middle arc chooses one cross-section mapping and both endpoints persist that same mapping.
assert 'groupMapping = ArcCrossSectionMapping.resolve' in binder
assert 'setEndpointFrame(ArcStationFrames.alignEndpoint(stations.get(0), startModel), groupMapping)' in binder
assert 'setEndpointFrame(ArcStationFrames.alignEndpoint(stations.get(stations.size()-1), endModel), groupMapping)' in binder
assert 'endpointLateralUsesWidth' in entity and 'endpointLateralSign' in entity and 'endpointVerticalSign' in entity
assert 'getEndpointLateralAxis' in endpoint and 'getEndpointVerticalAxis' in endpoint
assert 'ArcCrossSectionMapping.resolve' not in endpoint

# Connected railings keep partial side faces, but hidden longitudinal module caps are culled.
assert 'cullLongitudinalModuleCaps' in arc
assert '|| ConnectionStateHelper.isSupported(state)' in arc
assert 'if (cullLongitudinalModuleCaps)' in arc
assert 'isBoundaryFace(original, Axis.Q, 0.0)' in arc
assert 'isBoundaryFace(original, Axis.Q, 1.0)' in arc
assert 'prepared.qStart()' in arc and 'prepared.qEnd()' in arc
# Lateral/vertical whole-face cull stays restricted to full-cube-like models.
assert 'if (cullInternalTileFaces)' in arc

print('0.9.32 final trim / priority / endpoint mapping / railing-cap checks passed')
