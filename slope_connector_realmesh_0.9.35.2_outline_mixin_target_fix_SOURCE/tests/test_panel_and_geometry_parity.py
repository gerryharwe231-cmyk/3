#!/usr/bin/env python3
import hashlib, json, struct, zipfile
from pathlib import Path

ROOT=Path(__file__).parents[1]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

# These are the exact 0.9.23 source hashes supplied by the user.
EXPECTED={
 'src/main/java/com/slopeconnector/surface/mixin/ArcHudPromptMixin.java':'55429f588794b006b0de7457e7a3c8b4af7282627e61c1c36fec63489bc8738c',
}
for rel, expected in EXPECTED.items():
    actual=sha(ROOT/rel)
    assert actual==expected,(rel,actual,expected)

# Dimension internals stay compatible, but the visible names are intentionally swapped per user request.
dimension_screen=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java').read_text()
dimension_state=(ROOT/'src/main/java/com/slopeconnector/surface/dimensions/ArcDimensionClientState.java').read_text()
assert '上下厚度' in dimension_screen and '侧面宽度' in dimension_screen
assert '侧面宽度：' in dimension_state

# The complete 0.9.23 runtime jars remain byte-for-byte unchanged.
assert sha(ROOT/'libs/slopeconnector-0.9.19.jar')=='4809cef0f3cdb9602ab68a05b4989c0724199819ddfe41d166d195e5ea05a344'
assert sha(ROOT/'libs/slopeconnector-0.9.17.jar')=='463296740da08af102a072f5827f64aef9d093b938a2833f574b20a34697b5df'

# Compile-only 0.9.10 must be exactly the core nested in the user's runtime chain.
assert sha(ROOT/'libs/slopeconnector-0.9.10.jar')=='55b3e5b831972511dcb7b1cdae38f5a0bfcd5a61e0fae497667ec68466047f76'

# Extract UTF8 constants from the unchanged original ArcWandConfigScreen to audit every base control.
def utf8_constants(class_bytes):
    pos=8
    count=int.from_bytes(class_bytes[pos:pos+2],'big');pos+=2
    out=[];i=1
    while i<count:
        tag=class_bytes[pos];pos+=1
        if tag==1:
            n=int.from_bytes(class_bytes[pos:pos+2],'big');pos+=2
            out.append(class_bytes[pos:pos+n].decode('utf-8','replace'));pos+=n
        elif tag in (3,4):pos+=4
        elif tag in (5,6):pos+=8;i+=1
        elif tag in (7,8,16,19,20):pos+=2
        elif tag in (9,10,11,12,17,18):pos+=4
        elif tag==15:pos+=3
        else:raise AssertionError(('unknown constant-pool tag',tag))
        i+=1
    return out
with zipfile.ZipFile(ROOT/'libs/slopeconnector-0.9.10.jar') as z:
    strings=utf8_constants(z.read('com/slopeconnector/client/ArcWandConfigScreen.class'))
for expected in [
    '真实圆弧连接杖设置','方向 ◀','方向 ▶','当前面：\x01','两点弧向：\x01',
    '宽度 -','宽度 +','当前宽度：\x01','清空已选连接点','完成 / 返回游戏',
    '左侧：圆弧方式与朝向','右侧：宽度与操作',
    '两点模式生成单一真实圆弧；三点模式由第二点确定圆和弧度。',
    '关闭面板后，用连接杖依次右键放置连接点。'
]:
    assert expected in strings,expected

# 0.9.17's unchanged screen mixin is what provides the auto-trim button.
with zipfile.ZipFile(ROOT/'libs/slopeconnector-0.9.17.jar') as z:
    names=set(z.namelist())
assert 'com/slopeconnector/hotfix/mixin/ArcWandConfigScreenMixin.class' in names
assert 'com/slopeconnector/hotfix/client/ArcAutoTrimClientState.class' in names

# G is a real Fabric key binding now, not a constant rewrite of the old R binding.
client=(ROOT/'src/main/java/com/slopeconnector/surface/client/SurfaceRefineClient.java').read_text()
assert 'KeyBindingHelper.registerKeyBinding' in client
assert 'InputUtil.GLFW_KEY_G' in client
assert 'new ArcWandConfigScreen()' in client
assert 'new ModelRenderScreen()' in client
assert not (ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcPanelKeyMixin.java').exists()

# The reimplemented ModelArcWandHandler must never return: accepted Model Block clicks PASS into
# the unchanged original ArcSlopeWandItem, and only its BE-rejection expression is exempted.
assert not (ROOT/'src/main/java/com/slopeconnector/model/ModelArcWandHandler.java').exists()
common=(ROOT/'src/main/java/com/slopeconnector/surface/SurfaceRefineMod.java').read_text()
assert 'return ActionResult.PASS;' in common
assert 'ModelArcWandHandler' not in common
bypass=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcWandModelBlockEntityBypassMixin.java').read_text()
assert 'method_7884' in bypass and 'method_31709' in bypass
assert 'ModelSystemMod.isModelHolder(state) ? false : state.hasBlockEntity()' in bypass

# Pure-white model arcs must use the exact 0.9.23 prism renderer.  Only captured models switch to
# BakedModel deformation.  This prevents the white template from changing the old arc shape.
renderer=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonRendererMixin.java').read_text()
assert 'ModelSystemMod.isModelHolder(entity.getSourceState())' in renderer
assert 'ModelTemplateArcRenderer.render' in renderer
assert 'UnifiedSurfaceArcRenderer.renderReplacement' in renderer
assert 'ModelArcRenderer.renderReplacement' in renderer

# White model texture is the audited 16x16 opaque RGBA resource from 0.9.24.1.
assert sha(ROOT/'src/main/resources/assets/slopeconnector_surface_refine/textures/block/model_white.png') == \
       '8cce60052e08828a3bafc5a74665510101e25ccfd1544950762385a0c94ce9dc'

# Mixin config keeps all 0.9.23 functional mixins and adds only the model endpoint helpers.
config=json.loads((ROOT/'src/main/resources/slopeconnector_surface_refine.mixins.json').read_text())
for name in ['ConnectedArcGeneratorMixin','ArcRibbonDimensionMixin','ConnectedNeighborStateMixin']:
    assert name in config['mixins'],name
for name in ['ArcHudPromptMixin','ArcWandHoldingMixin','ArcDimensionScreenMixin','ArcRibbonRendererMixin','ConnectedArcRendererMixin']:
    assert name in config['client'],name
assert 'ArcWandModelBlockEntityBypassMixin' in config['mixins']
assert 'ModelNeighborConnectionMixin' in config['mixins']
print('0.9.23 panel + arc geometry parity checks passed')
