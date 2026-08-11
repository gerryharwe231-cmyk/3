# 0.9.35.2 Outline Mixin Target Fix

本版只修复 0.9.35.1 的第二个启动阶段 Mixin 崩溃，不改变 0.9.35 的 Z 坡、真实自动裁切、栏杆、碰撞和性能逻辑。

## 错误根因

最新错误报告显示：

```text
MixinApplyError: ArcRibbonProxyOutlineMixin
InvalidInjectionException:
could not find any targets matching 'getOutlineShape'
in com/slopeconnector/hotfix/ArcRibbonBlock
```

`ArcRibbonProxyOutlineMixin` 0.9.35.1 直接以 `ArcRibbonBlock` 为 Mixin target，并试图注入 `getOutlineShape`。

Minecraft 1.20.1 中这个方法实际声明在 `AbstractBlock`，ArcRibbonBlock只是继承它；Mixin不会把对子类target的方法注入自动解析成父类声明，因此启动阶段直接失败。

## 修复

现在改为：

```java
@Mixin(value = AbstractBlock.class, priority = 3020)
```

并注入：

```java
@Inject(method = "getOutlineShape", at = @At("HEAD"), cancellable = true, require = 0)
```

入口第一层严格过滤：

```java
if (state == null || state.getBlock() != ArcHotfixMod.ARC_RIBBON) return;
```

因此普通方块不会执行代理隐藏逻辑；只有 ArcRibbon collision proxy 才返回空 outline，避免玩家选中不可见碰撞代理。

`require = 0` 也保留为启动安全保护：这条辅助 outline 逻辑以后即便发生映射变化，也不应该让整个客户端因为 injection target 缺失直接崩溃。

## 同类 Mixin 审计

本次重新扫描 `slopeconnector_surface_refine.mixins.json` 中全部 mixin：

- `ArcRibbonGroupBreakMixin`：已经正确 target `AbstractBlock.onStateReplaced`；
- `ArcRibbonProxyOutlineMixin`：本版改为 `AbstractBlock.getOutlineShape`；
- `ConnectedNeighborStateMixin` / `ModelNeighborConnectionMixin`：直接 target `AbstractBlock.AbstractBlockState`；
- 其余 target 均为本模组实际声明方法或明确Minecraft类方法。

没有再保留直接 target `ArcRibbonBlock` 并注入继承方法的写法。

## 保持不变

0.9.35 原功能继续保留：

- Z轴真实水平 landing + 平滑坡；
- Z轴高度坡坡度随高差/水平距离变化；
- 真实几何自动裁切；
- ARC_TRIM 剩余碰撞；
- 楼梯/半砖/栏杆优先级；
- 铁栏杆端盖处理；
- collision proxy 不可选中；
- 单 render leader；
- PreparedQuad / chunk bucket / 宽厚大弧 LOD 性能优化。

## 工作流

```text
Build 0.9.35.2 Outline Mixin Target Fix
```
