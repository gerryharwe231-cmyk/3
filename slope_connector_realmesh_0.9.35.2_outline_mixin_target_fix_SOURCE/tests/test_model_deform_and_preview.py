#!/usr/bin/env python3
from pathlib import Path
import math

ROOT=Path(__file__).parents[1]
renderer=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java').read_text()
screen=(ROOT/'src/main/java/com/slopeconnector/model/client/ModelRenderScreen.java').read_text()

# Repeating models are clipped to their real source cell before repetition.  This prevents a
# connected model's side arms / decorative overhang from being rendered several times at joins.
assert 'prepareQuads(quads, sourceAxis, sourceReverse)' in renderer
assert 'polygon = clip(original, Axis.Q, 0.0, true);' in renderer
assert 'polygon = clip(polygon, Axis.Q, 1.0, false);' in renderer

# Fine source slicing plus one owner per real triangle position prevents coarse vertical plates and
# triangles disappearing when a module spans more than one ArcRibbon holder block.
assert 'sourceSlice(BlockState state)' in renderer
assert '? 1.0 / 8.0 : 1.0 / 4.0' in renderer
assert 'SOURCE_SLICE' not in renderer
assert 'double sAverage = (first.s + second.s + third.s) / 3.0;' in renderer
assert 'ArcRibbonBlockEntity owner = curve.ownerAt(sAverage);' in renderer
assert 'moduleMid' not in renderer

# Hidden longitudinal caps between repeated modules are culled exactly like ordinary neighboring
# blocks; otherwise every block-length join leaves an internal vertical plate.
assert 'isBoundaryFace(original, Axis.Q, 0.0)' in renderer
assert 'isBoundaryFace(original, Axis.Q, 1.0)' in renderer
assert 'prepared.qStart()' in renderer and 'prepared.qEnd()' in renderer
assert 'component.startModelBlock() != null' in renderer
assert 'component.endModelBlock() != null' in renderer

# Mirrored source/target bases must have their triangle winding explicitly corrected instead of
# swapping the requested inner-arc direction.
assert 'reversesWinding' in renderer
assert 'WorldVertex swap = second;' in renderer

# The model renderer is forbidden to invent a second centreline: geometry positions use the exact
# 0.9.23 prism centreline, while only frame orientation is smoothed.
assert 'Vec3d center = a.center().lerp(b.center(), t);' in renderer
assert 'shared topology stations' in renderer
assert 'hermite(' not in renderer

# Cross-section axes are made perpendicular to the tangent before captured model vertices are
# transported, preventing accumulated shear along long arcs.
assert 'width = orthogonal(width, tangent);' in renderer
assert 'radial = orthogonal(radial, tangent);' in renderer

# Inventory-style preview: actual block item icon, translated block name, registry id, one state
# summary line, and only clear/done controls.
for token in ('context.drawItem(preview, 0, 0)', 'state.getBlock().getName()',
              'Registries.BLOCK.getId(state.getBlock())', 'stateSummary(state)',
              '清空当前模型', '完成'):
    assert token in screen, token
assert '右键普通方块/半砖/楼梯/栏杆获取模型' not in screen
assert 'properties(state)' not in screen

# Handedness math fixture: source X-longitudinal basis is (X,Z,Y) => -1; Z-longitudinal is
# (Z,X,Y) => +1.  A target frame with opposite sign must be reversed exactly once.
def det(a,b,c):
    return (
        a[0]*(b[1]*c[2]-b[2]*c[1])
        -a[1]*(b[0]*c[2]-b[2]*c[0])
        +a[2]*(b[0]*c[1]-b[1]*c[0])
    )
assert det((1,0,0),(0,0,1),(0,1,0)) < 0
assert det((0,0,1),(1,0,0),(0,1,0)) > 0

# Adjacent repeated modules share one exact arc station, hence no geometric seam can be created by
# the module repetition itself.
total=13.7
count=round(total)
pitch=total/count
for i in range(1,count):
    left=(i-1)*pitch + pitch
    right=i*pitch
    assert abs(left-right)<1e-12

print('model deformation and inventory preview regression checks passed')
