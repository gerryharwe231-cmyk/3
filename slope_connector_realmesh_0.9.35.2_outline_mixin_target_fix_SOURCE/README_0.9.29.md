# Slope Connector Model Pipeline 0.9.29

本次版本针对你这次提到的六类问题做了定向修复与性能重构，重点是：

1. **端点顺序与终点异常修复**
   - `ArcComponentFinder` 现在会优先按端点模型块本身的朝向元数据重新校正整条弧的首尾顺序。
   - 当你直接点某个端点模型块时，会强制把该端点作为组件起点来重排，避免首尾反转导致的终点错位、缺口、反向端帽等问题。
   - 同时加入代表性中心线筛选，避免宽弧/厚弧把整条组件识别成多条并行链，减少终点乱套和整条弧识别失败。

2. **楼梯/台阶朝向优先级修复**
   - 楼梯、台阶仍然保留实际的 `facing / half / top-bottom` 等核心朝向信息。
   - 但楼梯的 `shape` 会统一冻结为 `STRAIGHT`，不再把原版邻接自动生成的 `inner/outer` 角楼梯形态直接带入弧方块。
   - 这样可以避免出现你图二那类箭头/T 字外凸，也能减少“端点与中段朝向相反”的问题。

3. **台阶/楼梯弧形切割链路稳定性增强**
   - `ArcComponentFinder` 和 `UnifiedSurfaceArcRenderer` 都新增了**代表性中心线提取**。
   - 宽度/厚度扩展后的并行 prism 不再全部参与主链排序，而是先去重抽取中心代表段。
   - 这会明显降低楼梯/台阶在宽弧、厚弧条件下退回白色原生弧面、或者直接穿模弧块的问题。

4. **图六右侧“侧面宽度”UI 排版修复**
   - 右侧底部按钮区整体加宽。
   - `侧面 - / 当前侧宽 / 侧面 +` 三个按钮现在和 `上下厚度` 一样获得完整可点击区域，不再被裁切。

5. **弧方块周围严重掉帧/卡顿的核心优化**
   - `ModelArcRenderer` 不再每 20 tick 强制整条弧重新编译一次变形网格。
   - 现在改成**按组件成员 revision 快照校验**，只有组件真实变动时才重建缓存。
   - `UnifiedSurfaceArcRenderer` 的 atlas 也取消了 20 tick 短 TTL，同样改成成员快照校验。
   - 这两处是当前最重的热点之一，优化后靠近弧方块时的无意义重复重建会大幅减少。

6. **测试补充**
   - 新增 `tests/test_0929_endpoint_cache_ui_state.py`，静态检查：
     - 端点顺序重排逻辑
     - 楼梯状态规范化
     - 渲染缓存快照机制
     - 右侧 UI 新排版

## 本次实际改动文件

- `src/main/java/com/slopeconnector/model/ArcComponentFinder.java`
- `src/main/java/com/slopeconnector/model/ModelStateResolver.java`
- `src/main/java/com/slopeconnector/model/client/ModelArcRenderer.java`
- `src/main/java/com/slopeconnector/hotfix/client/UnifiedSurfaceArcRenderer.java`
- `src/main/java/com/slopeconnector/surface/mixin/ArcDimensionScreenMixin.java`
- `tests/test_0928_dimensions_tiling_collision_state_priority.py`
- `tests/test_0929_endpoint_cache_ui_state.py`
- `gradle.properties`
- `src/main/resources/fabric.mod.json`

## 已执行的静态检查

已在源码目录执行：

- `python tests/test_0929_endpoint_cache_ui_state.py`
- `python tests/test_0928_dimensions_tiling_collision_state_priority.py`
- `python tests/test_model_deform_and_preview.py`

全部通过。

## 说明

这版已经把**端点顺序、楼梯状态、缓存重建、右侧 UI 裁切**这几块直接下手改了。

你提到的“**朝向选上/下且两个端点同高时会出现图五那种异常并在周围卡爆**”这一类问题，很大概率和此前的组件识别错链、重复重建、并行 prism 全量参与主链排序有关，这版已经对应做了修复。
