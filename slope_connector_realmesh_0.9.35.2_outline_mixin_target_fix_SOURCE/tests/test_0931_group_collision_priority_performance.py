#!/usr/bin/env python3
from pathlib import Path
import json

ROOT=Path(__file__).parents[1]

tags=(ROOT/'src/main/java/com/slopeconnector/model/ArcPrismTags.java').read_text()
group=(ROOT/'src/main/java/com/slopeconnector/model/ArcBuildGroupContext.java').read_text()
group_mixin=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonGroupTagMixin.java').read_text()
finder=(ROOT/'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
stations=(ROOT/'src/main/java/com/slopeconnector/model/ArcStationFrames.java').read_text()
collision=(ROOT/'src/main/java/com/slopeconnector/model/ArcCollisionProxyBuilder.java').read_text()
entity=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlockEntity.java').read_text()
resolver=(ROOT/'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
middle=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
endpoint=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelBlockRenderer.java').read_text()
mapping=(ROOT/'src/main/java/com/slopeconnector/model/ArcCrossSectionMapping.java').read_text()
public_block=(ROOT/'src/main/java/com/slopeconnector/model/ModelBlock.java').read_text()
endpoint_block=(ROOT/'src/main/java/com/slopeconnector/model/ModelEndpointBlock.java').read_text()
system=(ROOT/'src/main/java/com/slopeconnector/model/ModelSystemMod.java').read_text()
endpoint_service=(ROOT/'src/main/java/com/slopeconnector/model/ModelEndpointService.java').read_text()
render_distance=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRenderDistanceMixin.java').read_text()
wand=(ROOT/'src/main/java/com/slopeconnector/model/ModelRenderWandItem.java').read_text()
dimension=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonDimensionMixin.java').read_text()

# Logical group metadata persists exact user dimensions and the whole generated group identity.
for token in ('GROUP_MARKER_HINT = 121','groupWidthSpan','groupRadialSpan','groupInnerFace','groupId'):
    assert token in tags, token
assert 'ArcBuildGroupContext.begin' in dimension
assert 'ArcBuildGroupContext.finishAndTag' in dimension
assert 'ArcBuildGroupContext.record' in group_mixin
assert 'Map.copyOf' not in group  # generation metadata path stays simple/no render cache work

# Low/middle/high and left/middle/right holders are one component, not separate replacement strips.
assert 'logicalGroup != 0L' in finder
assert 'ArcPrismTags.groupId(other) != logicalGroup' in finder
assert 'Merge the ENTIRE logical group first' in finder
assert 'representativeSegments(candidates, exactWidthSpan, exactRadialSpan)' in finder
assert 'finiteSpan(exactWidthSpan' in finder and 'finiteSpan(exactRadialSpan' in finder
assert 'double exactWidthSpan' in finder and 'double exactRadialSpan' in finder
assert 'ArcGroupId' in entity

# Visual and collision cross-sections consume the exact persisted spans.
assert 'component.exactWidthSpan()' in stations
assert 'component.exactRadialSpan()' in stations
assert 'ArcStationFrames.build(component)' in collision
assert 'ArcStationFrames.section(stations.get(index))' in collision
assert 'addEndpointOccupancy' in collision
assert 'ModelSystemMod.isModelHolder(existing)' in collision
assert 'ArcPrismTags.groupMarker(component.groupId()' in collision

# Model priority chain and widening transparency fix.
idx_stair=resolver.index('// Priority 1: stairs/slabs')
idx_connected=resolver.index('// Priority 2: connected profiles')
idx_generic=resolver.index('// Priority 3: ordinary texture/model continuity')
assert idx_stair < idx_connected < idx_generic
assert 'canCullInternalTileFaces' in resolver
assert '!isStairOrSlab(state) && !ConnectionStateHelper.isSupported(state)' in resolver
assert 'if (cullInternalTileFaces)' in middle
assert 'if (cullInternalTileFaces)' in endpoint
assert 'ArcCrossSectionMapping.resolve' in middle
assert 'getEndpointLateralAxis' in endpoint
assert 'getEndpointVerticalAxis' in endpoint
assert 'ArcCrossSectionMapping.resolve' not in endpoint
assert 'record Mapping' in mapping

# Straight modules are no longer needlessly sliced; curved stairs/railings keep extra detail.
assert 'sourceSliceForModule' in middle
assert 'if (dot > 0.99995) return 1.0;' in middle
assert '? 1.0 / 8.0 : 1.0 / 4.0' in middle

# Dense public ModelBlocks are true ordinary opaque Blocks with no BE capability at all.
assert 'public final class ModelBlock extends Block {' in public_block
assert 'BlockWithEntity' not in public_block.replace('BlockWithEntity.','')
assert 'public final class ModelEndpointBlock extends BlockWithEntity' in endpoint_block
assert 'MODEL_ENDPOINT_BLOCK' in system
assert 'FabricBlockEntityTypeBuilder.create(ModelBlockEntity::new, MODEL_ENDPOINT_BLOCK)' in system
assert 'state = ModelSystemMod.MODEL_ENDPOINT_BLOCK.getDefaultState();' in endpoint_service
states=json.loads((ROOT/'src/main/resources/assets/slopeconnector_surface_refine/blockstates/model_block.json').read_text())
assert list(states['variants']) == ['']

# Captured-model groups use one central render leader. Followers/proxies never reach expensive BER.
assert 'RENDER_LEADER_HINT = 122' in tags
assert 'stampRenderLeader' in (ROOT/'src/main/java/com/slopeconnector/model/ArcEndpointMetadataBinder.java').read_text()
assert 'ArcPrismTags.isRenderLeader(entity)' in render_distance
assert 'if (!ArcPrismTags.isRenderLeader(entity)) return false;' in render_distance
assert 'renderLeaderRadius' in render_distance
assert 'boolean consolidated = handle.renderLeader != null' in middle
assert 'nearbyEntries(handle.byOwner, handle.ownersByChunk, viewer)' in middle

# Model replacement syncs custom data without triggering full neighbour physics for every holder.
assert 'world.updateListeners(ribbon.getPos(),world.getBlockState(ribbon.getPos()),world.getBlockState(ribbon.getPos()),2)' in wand

# Pathological UP/DOWN same-height builds remain rejected before group/proxy generation.
assert 'face == Direction.UP || face == Direction.DOWN' in dimension
assert 'startBlock.getY() == endBlock.getY()' in dimension
assert '连接失败' in dimension

print('0.9.31 logical-group / full collision / priority / render-performance checks passed')
