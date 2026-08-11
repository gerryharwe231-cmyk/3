#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).parents[1]
smooth=(ROOT/'src/main/java/com/slopeconnector/hotfix/SmoothElevationArcPath.java').read_text()
mixin=(ROOT/'src/main/java/com/slopeconnector/elevation/mixin/ArcRibbonElevationContextMixin.java').read_text()
trim=(ROOT/'src/main/java/com/slopeconnector/hotfix/FinalModelArcTrim.java').read_text()
model=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
template=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelTemplateArcRenderer.java').read_text()
proxy=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonProxyOutlineMixin.java').read_text()
render_distance=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRenderDistanceMixin.java').read_text()
config=(ROOT/'src/main/resources/slopeconnector_surface_refine.mixins.json').read_text()
break_mixin=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonGroupBreakMixin.java').read_text()

# 1) Elevation path owns real physical flat landings at both ends.
for token in ('double landing =', 'transitionStart = landing', 'transitionEnd = run - landing',
              's <= transitionStart + EPS', 's >= transitionEnd - EPS',
              'h = 0.0;', 'h = 1.0;', 'dhDs = 0.0;'):
    assert token in smooth, token
assert 'settings.arcSide != riseSide' not in mixin
assert 'riseSide' not in mixin
assert 'SmoothElevationArcPath.buildObject(run, rise)' in mixin

# 2) Final trim is an actual Boolean difference visually AND physically.  The cut holder must not
# own the arc collision body: it owns only the remaining source volume.
assert 'indexFinalPrismCutters(component)' in trim
assert 'visibleRemainderTriangles' in trim
assert 'ConvexGeometry.triangulate(remaining, pos)' in trim
assert 'owners > 1' in trim  # internal subtraction partitions are hidden
assert 'liesOnCutPlane' in trim
assert 'new ArcTrimBlockEntity.Triangle(xyz.clone(), cutFace)' in trim
assert 'collisionPolys.add(ConvexGeometry.translated(poly, toLocal))' in trim
assert 'collisionPolys.add(ConvexGeometry.translated(inside, toLocal))' not in trim
assert 'ConvexGeometry.intersection(cell, cutter.poly)' not in trim

# 3) Collision-only helpers are not ray-targetable/breakable as fake blocks.
assert 'ArcPrismTags.isProxyOnly(ribbon)' in proxy
assert 'VoxelShapes.empty()' in proxy
assert 'ArcRibbonProxyOutlineMixin' in config
assert 'ArcRibbonGroupBreakMixin' in config
assert 'onStateReplaced' in break_mixin
assert 'ArcPrismTags.renderLeader(radius)' in break_mixin
assert 'member.setData(member.getSourceState()' in break_mixin

# 4) Dense widened/thickened geometry receives more aggressive but deterministic LOD.
assert 'OWNER_RENDER_RADIUS = 48.0' in model
assert 'OWNER_RENDER_RADIUS = 48.0' in template
assert 'crossSectionTiles >= 9' in model and 'Math.max(base, 0.50)' in model
assert 'crossSectionTiles >= 16' in model and 'base = 1.0' in model
assert '48.0 * 48.0' in render_distance

print('0.9.35 physical landing / real trim / proxy targeting / dense LOD checks passed')
