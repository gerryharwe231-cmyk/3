#!/usr/bin/env python3
from pathlib import Path
import json
ROOT=Path(__file__).parents[1]

finder=(ROOT/'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
binder=(ROOT/'src/main/java/com/slopeconnector/model/ArcEndpointMetadataBinder.java').read_text()
stations=(ROOT/'src/main/java/com/slopeconnector/model/ArcStationFrames.java').read_text()
block=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlock.java').read_text()
entity=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlockEntity.java').read_text()
endpoint_renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()
model_renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
unified=(ROOT/'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java').read_text()
selector=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java').read_text()
dimension=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()
screen=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
orientation=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcPlacementOrientationScreenMixin.java').read_text()

# Full clipped cross-section is merged, not reduced to one representative one-block prism.
assert 'mergeGroup(List<RawSegment> group,' in finder
assert 'mergeCrossSection' in finder
assert 'maxW-minW' in finder and 'maxR-minR' in finder
assert 'Merge the ENTIRE logical group first' in finder

# Endpoint metadata uses actual start/end tangent before frame alignment and the station center is
# snapped onto the actual endpoint block face.
assert 'dominantDirection(towardArc)' in binder
assert 'setArcMetadata(dominantDirection(towardArc), innerFace)' in binder
assert 'Vec3d faceCenter = endpointCenter.add' in stations
assert 'return new Station(faceCenter' in stations

# Standalone ModelBlocks are now true ordinary Blocks.  Only the hidden internal endpoint block
# owns a BlockEntity/BER, so dense template walls never enter the BE path.
endpoint_block=(ROOT/'src/main/java/com/slopeconnector/model/ModelEndpointBlock.java').read_text()
system=(ROOT/'src/main/java/com/slopeconnector/model/ModelSystemMod.java').read_text()
assert 'public final class ModelBlock extends Block {' in block
assert 'extends BlockWithEntity' in endpoint_block
assert 'MODEL_ENDPOINT_BLOCK' in system
assert 'FabricBlockEntityTypeBuilder.create(ModelBlockEntity::new, MODEL_ENDPOINT_BLOCK)' in system
assert 'isInRenderDistance(ModelBlockEntity entity' in endpoint_renderer
assert 'rendersOutsideBoundingBox(ModelBlockEntity entity)' in endpoint_renderer
states=json.loads((ROOT/'src/main/resources/assets/slopeconnector_surface_refine/blockstates/model_block.json').read_text())
assert '' in states['variants']
endpoint_states=json.loads((ROOT/'src/main/resources/assets/slopeconnector_surface_refine/blockstates/model_endpoint.json').read_text())
assert '' in endpoint_states['variants']

# Collision-only ArcRibbon holders never enter expensive topology/model rendering.
assert 'ArcPrismTags.isProxyOnly(entity)' in selector

# No once-per-second component rebuild on white templates; expensive render caches are O(1) per BE.
assert 'CACHE_TTL' not in template
assert 'CachedTemplate' in template
assert 'MeshHandle' in model_renderer
assert 'CachedAtlas' in unified
assert 'cached.matches(entity)' in model_renderer
assert 'cached.revision() == entity.getRenderRevision()' in unified
assert 'directionalLights' in model_renderer

# UP/DOWN + equal endpoint height is explicitly rejected before the embedded generator can create
# pathological fan geometry/proxy storms.
assert 'face == Direction.UP || face == Direction.DOWN' in dimension
assert 'startBlock.getY() == endBlock.getY()' in dimension
assert '连接失败' in dimension
assert 'new ArcRibbonGenerator.Result(0, 0, 0, 0' in dimension

# Side-width row mirrors the two-row treatment of the upper thickness controls.
assert '.dimensions(rightX, y, 64, 20)' in screen
assert '.dimensions(rightX + 66, y, 64, 20)' in screen
assert '.dimensions(rightX, y + 24, 130, 20)' in screen
# Placement-orientation label/button are moved below auto-trim instead of painting over it.
assert 'dimensions(18, 218, 130, 20)' in orientation
assert 'Text.literal("放置朝向"), 18, 205' in orientation

print('0.9.30 endpoint visibility / same-height rejection / performance / UI checks passed')
