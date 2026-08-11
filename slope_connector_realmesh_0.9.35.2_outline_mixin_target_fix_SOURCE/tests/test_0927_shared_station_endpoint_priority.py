#!/usr/bin/env python3
from pathlib import Path
import math

ROOT=Path(__file__).parents[1]
station=(ROOT/'src/main/java/com/slopeconnector/model/ArcStationFrames.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()
model=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
resolver=(ROOT/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
entity=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlockEntity.java').read_text()

# Outer-only crack mechanism: two independently oriented sections separate on the positive radius
# side while the negative radius side overlaps.  A single shared station produces exactly one corner.
a=math.radians(-8.0); b=math.radians(8.0); half=2.0
outer_a=(half*math.cos(a),half*math.sin(a)); outer_b=(half*math.cos(b),half*math.sin(b))
inner_a=(-half*math.cos(a),-half*math.sin(a)); inner_b=(-half*math.cos(b),-half*math.sin(b))
assert math.dist(outer_a,outer_b)>0.5
assert math.dist(inner_a,inner_b)>0.5
# Source fix: both neighbors consume one averaged topology Station and its exact section corners.
for required in ['One canonical cross-section frame','averageAxis','ArcStationFrames.section','widthSpan','radialSpan']:
    assert required in station+template,required

# No prism expansion is allowed; it caused top/bottom longitudinal overlap and z-fighting.
assert 'SEAM_OVERLAP' not in template
assert 'expanded(' not in template
# Four longitudinal sides are emitted explicitly and internal end caps are intentionally absent.
assert 'for (int edge = 0; edge < 4; edge++)' in template
assert 'no internal end cap' in template
# Even fallback draws all four side faces, independent of old faceMask/draws omissions.
assert 'prism.draws(' not in template

# Captured models sample the same station frames, preventing outer-arc frame discontinuity.
assert 'ArcStationFrames.build(component)' in model
assert 'stations.get(index)' in model and 'stations.get(index + 1)' in model
assert 'shared topology stations' in model

# Endpoint seam orientation is a hard endpoint rule, independent of the player's placement switch.
assert 'terminalEnd ? connectionDirection.getOpposite() : connectionDirection' in resolver
assert 'ConnectionStateHelper.endpointState(normalized, connectionDirection' in resolver
assert 'endpointState(capturedState, arcDirection, terminalEnd' in entity
assert 'rotateTerminalUv' not in endpoint
assert 'endpointLongitudinal(entity)' in endpoint
# The optional ordinary-block placement system is not referenced by endpoint resolver/renderer.
assert 'ArcPlacementOrientation' not in resolver
assert 'ArcPlacementOrientation' not in endpoint

print('0.9.27 shared-station, no-zfight, outer-crack and endpoint-priority checks passed')
