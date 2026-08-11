#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).parents[1]
block=(root/'src/main/java/com/slopeconnector/model/ModelBlock.java').read_text()
endpoint_block=(root/'src/main/java/com/slopeconnector/model/ModelEndpointBlock.java').read_text()
mod=(root/'src/main/java/com/slopeconnector/model/ModelSystemMod.java').read_text()
renderer=(root/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
arc=(root/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
lighting=(root/'src/main/java/com/slopeconnector/model/client/ModelRenderLighting.java').read_text()
state=(root/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()

# Invisible/skinned endpoint holders must neither occlude neighboring terrain nor self-darken.
assert 'MODEL_ENDPOINT_BLOCK' in mod and '.nonOpaque().dropsNothing().strength(0.8f)' in mod
assert 'getCullingShape' in endpoint_block
assert 'return VoxelShapes.empty();' in endpoint_block
assert 'public final class ModelBlock extends Block {' in block

# Endpoint renderer no longer goes through vanilla renderBlockAsEntity (which used a different
# shade/AO path than the curved middle renderer).
assert 'renderBlockAsEntity' not in renderer
assert 'BakedQuad' in renderer
assert 'ModelRenderLighting.sample' in renderer
assert 'ModelRenderLighting.sample' in arc

# Undirected cube/material endpoints are rotated with the arc tangent.  The terminal endpoint is
# the module AFTER the arc and therefore uses the opposite longitudinal direction.
assert 'endpointLongitudinal' in renderer
assert 'entity.isTerminalEnd()' in renderer
assert 'return entity.isTerminalEnd() ? towardArc.getOpposite() : towardArc;' in renderer
assert 'ArcComponentFinder' not in renderer
assert 'hasExplicitOrientation' in renderer
assert 'public static boolean hasExplicitOrientation' in state

# Endpoint texture continuity is encoded in the ordered endpoint orientation itself; no separate
# UV-only quarter turn is allowed because it can double-rotate after state/geometry alignment.
assert 'rotateTerminalUv' not in renderer
assert 'terminalEnd ? connectionDirection.getOpposite() : connectionDirection' in state

# Both endpoint and middle sample the open side of the actual rendered face through one function.
assert 'worldCenter.add(normal.x * 0.58' in lighting
assert 'LightmapTextureManager.pack' in lighting

print('endpoint lighting / UV orientation / culling regression checks passed')

entity=(root/'src/main/java/com/slopeconnector/model/ModelBlockEntity.java').read_text()
wand=(root/'src/main/java/com/slopeconnector/model/ModelRenderWandItem.java').read_text()
assert 'nbt.putBoolean("TerminalEnd", terminalEnd)' in entity
assert 'terminalEnd = nbt.getBoolean("TerminalEnd")' in entity
assert 'skinEndpoint(world,component.startModelBlock(),captured' in wand
assert 'skinEndpoint(world,component.endModelBlock(),captured' in wand
