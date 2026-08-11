#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).parents[1]

dimension=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()
selector=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()
finder=(ROOT/'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
atlas=(ROOT/'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
connection=(ROOT/'src/main/java/com/slopeconnector/surface/ConnectionStateHelper.java').read_text()
neighbor=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ModelNeighborConnectionMixin.java').read_text()
panel=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcPlacementOrientationScreenMixin.java').read_text()
placement=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/BlockItemPlacementOrientationMixin.java').read_text()
service=(ROOT/'src/main/java/com/slopeconnector/surface/orientation/PlacedOrientationService.java').read_text()

# The template geometry is repaired before it becomes collision/render data. A full ModelBlock cell
# can never collapse below one block across either cross-section axis.
assert 'SLOPECONNECTOR_SURFACE$MODEL_TEMPLATE' in dimension
assert 'wMax - wMin < 0.999' in dimension
assert 'nMax - nMin < 0.999 * scale' in dimension
assert 'buildPrism(c0, c1, r0, r1, w' in dimension

# Pure-white ModelBlocks use a dedicated template renderer and captured-model failures still retain
# the old visible fallback.  0.9.27 may canonicalize shared stations before drawing the template.
assert 'ModelTemplateArcRenderer.render' in selector
assert 'renderFallback' in template
assert 'UnifiedSurfaceArcRenderer.renderReplacement' in selector # still fallback for captured failures

# Generated straight/arc nodes use a wider *topology* tolerance and the actual clustered node point
# is copied into the model curve. This turns a near join into one literal shared station.
assert 'TOPOLOGY_ENDPOINT_EPS = 0.08' in finder
assert 'ordered.start().x()' in finder and 'ordered.end().x()' in finder
assert 'TOPOLOGY_ENDPOINT_EPS = 0.08f' in atlas
assert 'ordered.start().x()' in atlas and 'ordered.end().x()' in atlas

# Terminal endpoint orientation is derived from ordered arc direction.  It must not depend on an
# independent UV-only quarter-turn, because that can double-rotate after the model/state is aligned.
resolver=(ROOT/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
assert 'terminalEnd ? connectionDirection.getOpposite() : connectionDirection' in resolver
assert 'endpointLongitudinal' in endpoint
assert 'rotateTerminalUv' not in endpoint

# View-oriented placement remains an additive switch on the original G panel and still rewrites
# either explicit facing/axis state or the directionless baked model's visual orientation.
assert '视角定向放置' in (ROOT/'src/main/java/com/slopeconnector/surface/orientation/ArcPlacementOrientationClientState.java').read_text()
assert 'ArcPlacementOrientationClientState.toggle()' in panel
assert 'player.getHorizontalFacing()' in placement
assert 'quarterTurnsFromNorth' in service
assert 'applyExplicitState' in service

# Conquest compatibility is class+resource-family based. Boolean-arm families use native update;
# axis/facing-only Balustrade/Railings are aligned longitudinally instead of given fake arms.
assert 'getClass().getName().toLowerCase' in connection
assert 'orientationOnlyConnectedProfile' in connection
assert 'nativeUpdateWithRepresentedNeighbor' in neighbor
assert 'alignAxisOrFacing(nativeResult,direction)' in neighbor

print('0.9.26/0.9.27 straight/full-prism, seam-weld, terminal-priority and Conquest checks passed')
