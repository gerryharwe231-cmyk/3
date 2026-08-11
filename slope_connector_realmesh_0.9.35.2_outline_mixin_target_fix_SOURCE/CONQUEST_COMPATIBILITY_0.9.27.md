# Conquest Reforged 1.5.2 connection audit for 0.9.26

The uploaded `ConquestReforged-fabric-1.20.1-1.5.2(1).jar` was inspected directly.

## Runtime classes inspected

- `com.conquestrefabricated.content.blocks.block.Fence`
  - extends vanilla FenceBlock;
  - use vanilla N/E/S/W neighbour connection semantics.
- `com.conquestrefabricated.content.blocks.block.glass.Pane`
  - extends PaneBlock;
  - overrides `getStateForNeighborUpdate` and has custom attach rules.
- `com.conquestrefabricated.content.blocks.block.decor.FenceLayered`
  - extends PaneBlock;
  - custom `canConnectTo` + neighbour update.
- `com.conquestrefabricated.content.blocks.block.WallOld`
  - extends WallBlock;
  - custom attach and neighbour update.
- `com.conquestrefabricated.content.blocks.block.WallNew`
  - custom wall implementation;
  - custom directional wall-shape enum and neighbour update.
- `com.conquestrefabricated.content.blocks.block.Balustrade`
  - PillarBlock style;
  - uses `axis=x/y/z`, not N/E/S/W arms.
- `com.conquestrefabricated.content.blocks.block.decor.Railings`
  - horizontal-directional shape;
  - uses `facing + open`, not N/E/S/W neighbour arms.

## Resource scan

1791 blockstate JSON files matched fence / railing / balustrade / pane / wall / bars / lattice terms:

- 144 four-way N/E/S/W variants;
- 359 axis variants;
- 453 facing variants;
- 835 other/specialized variants.

Examples inspected directly:

- `birch_wood_railing_fence.json`: 16 combinations of north/east/south/west.
- `horizontal_birch_wood_railing.json`: facing=north/east/south/west + open=true/false.
- `birch_wood_plank_balustrade.json`: axis=x/y/z.

## 0.9.26 behavior

1. N/E/S/W families receive the real represented ModelBlock state and run their own native neighbour-update method.
2. If native logic still returns the old state, the existing property fallback is used only for actual N/E/S/W arms.
3. Axis/facing-only profiles are **not** given fake connection arms. Their real axis/facing is aligned to the direction of the adjacent skinned ModelBlock.
4. Family detection now considers both the registry id and actual Java block class name, covering Conquest variants whose resource id is not descriptive enough.
5. Re-skinning an endpoint still explicitly notifies neighbours so later-placed ordinary railing/fence/wall blocks recalculate immediately.
