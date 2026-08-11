#!/usr/bin/env python3
from pathlib import Path
import json

ROOT=Path(__file__).parents[1]

# Long-arc topology: source must use shared endpoint graph and the renderer/component finder must not
# use the old 0.22-nearest path for ordering/discovery.
helper=(ROOT/'src/main/java/com/slopeconnector/surface/geometry/SegmentChainOrder.java').read_text()
finder=(ROOT/'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
renderer=(ROOT/'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java').read_text()
assert 'build(input, epsilon)' in helper
assert 'previousDirection.dot(direction)' in helper
assert 'TOPOLOGY_ENDPOINT_EPS = 0.08' in finder
assert 'SegmentChainOrder.order' in finder
assert 'SegmentChainOrder.order' in renderer
assert 'TOPOLOGY_ENDPOINT_EPS = 0.08' in renderer

# View-oriented placement is a real global placement mechanic, not another fake text label.
common=(ROOT/'src/main/java/com/slopeconnector/surface/orientation/PlacedOrientationService.java').read_text()
placement=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/BlockItemPlacementOrientationMixin.java').read_text()
client=(ROOT/'src/main/java/com/slopeconnector/surface/orientation/PlacedOrientationClientCache.java').read_text()
model=(ROOT/'src/main/java/com/slopeconnector/surface/client/orientation/ViewOrientationBakedModel.java').read_text()
plugin=(ROOT/'src/main/java/com/slopeconnector/surface/client/orientation/ViewOrientationModelPlugin.java').read_text()
panel=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcPlacementOrientationScreenMixin.java').read_text()
mod=(ROOT/'src/main/java/com/slopeconnector/surface/SurfaceRefineMod.java').read_text()
assert '@Mixin(BlockItem.class)' in placement
assert 'ArcPlacementOrientationSettings.enabled(player)' in placement
assert 'player.getHorizontalFacing()' in placement
assert 'PersistentState' in (ROOT/'src/main/java/com/slopeconnector/surface/orientation/PlacedOrientationState.java').read_text()
assert 'ModelLoadingPlugin.register' in plugin
assert 'context.pushTransform' in model and 'context.popTransform' in model
assert 'quad.pos' in model and 'quad.normal' in model
# Cull face setter also changes nominalFace in Fabric; originals must be cached before either setter.
assert 'Direction originalCull = quad.cullFace();' in model
assert 'Direction originalNominal = quad.nominalFace();' in model
assert model.index('Direction originalCull = quad.cullFace();') < model.index('quad.cullFace(rotate(originalCull, turns));')
assert 'Block.NOTIFY_LISTENERS | Block.REDRAW_ON_MAIN_THREAD' in client
assert 'case NORTH -> Direction.EAST' in model
assert 'ArcPlacementOrientationClientState.label()' in panel
assert '视角定向放置' in (ROOT/'src/main/java/com/slopeconnector/surface/orientation/ArcPlacementOrientationClientState.java').read_text()
assert '.dimensions(18, 218, 130, 20)' in panel
assert 'vieworient' in mod
assert 'orientation_update' in common
assert 'orientation_setting' in common
assert 'ClientPlayNetworking.registerGlobalReceiver' in client

# Existing G and 0.9.23 controls must remain; new switch is additive only.
base_panel=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
surface_client=(ROOT/'src/main/java/com/slopeconnector/surface/client/SurfaceRefineClient.java').read_text()
assert '上下 -' in base_panel and '上下 +' in base_panel
assert '侧面 -' in base_panel and '侧面 +' in base_panel
assert '上下厚度' in base_panel and '侧面宽度' in base_panel
assert 'KeyBindingHelper.registerKeyBinding' in surface_client
assert 'InputUtil.GLFW_KEY_G' in surface_client
assert 'new ArcWandConfigScreen()' in surface_client

# Conquest/vanilla connection bridge: native neighbor update is always attempted with the represented
# model state. Axis/facing are orientation only and are no longer abused as fake connection arms.
connection=(ROOT/'src/main/java/com/slopeconnector/surface/ConnectionStateHelper.java').read_text()
neighbor=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ModelNeighborConnectionMixin.java').read_text()
wand=(ROOT/'src/main/java/com/slopeconnector/model/ModelRenderWandItem.java').read_text()
for word in ['fence','railing','railings','balustrade','baluster','bars','pane','wall']:
    assert f'"{word}"' in connection
assert 'result.getStateForNeighborUpdate(direction, neighbor, access, pos, neighborPos)' in connection
assert 'nativeUpdateWithRepresentedNeighbor' in neighbor
assert 'return state;' in connection[connection.index('public static BlockState forceConnection'):connection.index('public static boolean shouldForce')]
assert 'world.updateNeighborsAlways(pos, ModelSystemMod.MODEL_ENDPOINT_BLOCK)' in wand
assert 'orientationOnlyConnectedProfile' in connection
assert 'alignAxisOrFacing(nativeResult,direction)' in neighbor

# Rotation convention: original NORTH => player-facing N/E/S/W = 0/90/180/270 degrees.
def rotate_xz(x,z,turns):
    for _ in range(turns%4): x,z=1-z,x
    return round(x,6),round(z,6)
# center of original north face should become east/south/west in order.
assert rotate_xz(.5,0,0)==(.5,0)
assert rotate_xz(.5,0,1)==(1,.5)
assert rotate_xz(.5,0,2)==(.5,1)
assert rotate_xz(.5,0,3)==(0,.5)

print('0.9.26 welded topology + view orientation + native connection checks passed')
