#!/usr/bin/env python3
from pathlib import Path
p=Path(__file__).parents[1]/'src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java'
text=p.read_text(encoding='utf-8')
assert 'new LinkedHashMap<>()' in text
assert 'import java.util.LinkedHashMap;' in text
print('0.9.29.2 LinkedHashMap compile-import regression passed')
