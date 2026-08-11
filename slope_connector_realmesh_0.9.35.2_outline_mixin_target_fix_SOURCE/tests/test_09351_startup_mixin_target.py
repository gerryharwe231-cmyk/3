#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).parents[1]
s=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonGroupBreakMixin.java').read_text()
assert 'import net.minecraft.block.AbstractBlock;' in s
assert '@Mixin(value = AbstractBlock.class' in s
assert '@Mixin(value = Block.class' not in s
assert '@Inject(method = "onStateReplaced", at = @At("HEAD"), require = 0)' in s
assert 'if (state.getBlock() != ArcHotfixMod.ARC_RIBBON' in s
print('0.9.35.1 startup mixin target guard passed')
