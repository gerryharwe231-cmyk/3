#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).parents[1]

component = (ROOT / 'src/main/java/com/slopeconnector/model/ArcComponentFinder.java').read_text()
assert 'representativeSegments(List<RawSegment> candidates,' in component
assert 'orientToStoredEndpointDirection' in component
assert 'orientToPreferredEndpoint' in component
assert 'return towardArc.dotProduct(first) < 0.0 ? reverse(component) : component;' in component
assert 'return endDistance + 1.0E-6 < startDistance ? reverse(component) : component;' in component

state_resolver = (ROOT / 'src/main/java/com/slopeconnector/model/ModelStateResolver.java').read_text()
assert 'canonicalSourceState' in state_resolver
assert 'StairShape.STRAIGHT' in state_resolver
assert 'if (isStairOrSlab(normalized)) return normalized;' in state_resolver

model_renderer = (ROOT / 'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
assert 'MeshHandle' in model_renderer
assert 'memberRevisions' in model_renderer
assert 'cached.matches(entity)' in model_renderer
assert 'CACHE_TTL' not in model_renderer
assert 'MeshHandle.empty(state)' in model_renderer

unified_renderer = (ROOT / 'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java').read_text()
assert 'representativeSegments(List<RawSegment> candidates)' in unified_renderer
assert 'CachedAtlas' in unified_renderer
assert 'cached.revision() == entity.getRenderRevision()' in unified_renderer
assert 'CachedAtlas' in unified_renderer
assert 'tick - cached.builtTick' not in unified_renderer
assert 'new CachedAtlas(member.getRenderRevision()' in unified_renderer

screen = (ROOT / 'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
assert 'this.width - 148' in screen
assert '.dimensions(rightX, y, 64, 20)' in screen
assert '.dimensions(rightX + 66, y, 64, 20)' in screen
assert '.dimensions(rightX, y + 24, 130, 20)' in screen
assert 'context.fill(this.width - 158, 160, this.width - 8, 216' in screen

print('0.9.29 endpoint ordering + cache + stair-state + UI checks passed')
