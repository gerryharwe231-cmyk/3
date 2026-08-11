#!/usr/bin/env python3
from pathlib import Path
import json

ROOT = Path(__file__).parents[1]
configs = list((ROOT/'src/main/resources').glob('*mixins*.json'))
assert configs, 'no mixin configs found'

runtime_roots = {
    'com.slopeconnector.hotfix',
    'com.slopeconnector.model',
    'com.slopeconnector.surface',
    'com.slopeconnector.connected',
    'com.slopeconnector.client',
}

for cfg in configs:
    data = json.loads(cfg.read_text())
    package = data.get('package', '')
    assert package, f'{cfg.name}: missing package'
    assert package not in runtime_roots, f'{cfg.name}: mixin package illegally owns runtime package {package}'
    # Every outer patch Mixin package must explicitly end in .mixin.  This prevents a config from
    # turning ordinary runtime helpers/entrypoints into forbidden Mixin-package classes again.
    assert package.endswith('.mixin'), f'{cfg.name}: unsafe mixin package {package}'
    package_dir = ROOT/'src/main/java'/Path(*package.split('.'))
    assert package_dir.is_dir(), f'{cfg.name}: source package missing: {package_dir}'

# Regression for the 2026-08-11 startup crash.
elevation = json.loads((ROOT/'src/main/resources/slopeconnector_elevation.mixins.json').read_text())
assert elevation['package'] == 'com.slopeconnector.elevation.mixin'
assert not (ROOT/'src/main/java/com/slopeconnector/hotfix/ArcRibbonElevationContextMixin.java').exists()
assert (ROOT/'src/main/java/com/slopeconnector/elevation/mixin/ArcRibbonElevationContextMixin.java').exists()

# Real runtime entrypoint/helper classes must stay outside every declared Mixin package.
fabric = json.loads((ROOT/'src/main/resources/fabric.mod.json').read_text())
for entrypoint in fabric.get('entrypoints', {}).get('main', []):
    for cfg in configs:
        package = json.loads(cfg.read_text()).get('package', '')
        assert not (entrypoint == package or entrypoint.startswith(package + '.')), (entrypoint, package)

print('0.9.34.1 mixin package isolation checks passed')
