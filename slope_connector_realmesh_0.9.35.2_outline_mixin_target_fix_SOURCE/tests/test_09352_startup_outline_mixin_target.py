#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).parents[1]
text=(ROOT/'src/main/java/com/slopeconnector/surface/mixin/ArcRibbonProxyOutlineMixin.java').read_text()
assert '@Mixin(value = AbstractBlock.class' in text
assert 'import net.minecraft.block.AbstractBlock;' in text
assert 'ArcRibbonBlock.class' not in text
assert '@Inject(method = "getOutlineShape"' in text
assert 'require = 0' in text
assert 'state.getBlock() != ArcHotfixMod.ARC_RIBBON' in text
print('0.9.35.2 outline mixin target startup guard passed')
