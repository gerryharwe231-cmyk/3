# 0.9.24.2 — 0.9.23 面板与连接逻辑逐项核对

本版以用户上传的 **0.9.23 源码**为底座，以下文件保持字节级/源码哈希一致：

- `ArcDimensionScreenMixin.java`
- `ArcDimensionClientState.java`
- `ArcDimensionSettings.java`
- `ArcHudPromptMixin.java`
- `libs/slopeconnector-0.9.19.jar`
- `libs/slopeconnector-0.9.17.jar`

原始 `ArcWandConfigScreen` 仍来自完全相同的 0.9.10 核心 jar。

## G 面板中必须保留的功能

1. **两点 / 三点模式切换**
   - 两点：自动轴对称
   - 三点：第二点定弧度
2. **方向 ◀**
3. **方向 ▶**
4. **当前面 / 内弧朝向**
5. **两点弧向：正向 / 反向**
6. **自动弧边裁切：开 / 关**（由原 0.9.17 屏幕扩展提供）
7. **左右 -**
8. **左右 +**
9. **左右宽度当前值**
10. **上下 -**
11. **上下 +**
12. **上下厚度当前值**
13. **清空已选连接点**
14. **完成 / 返回游戏**
15. 原底部两点/三点使用说明保持不变。

## 本版只允许的连接杖行为变化

原 `ArcSlopeWandItem` 的点位阶段、两点/三点逻辑、方向、弧向、宽度、清空逻辑、生成方法都不重写。

只增加两个前置条件：

- 点击非模型方块：拒绝继续。
- 点击模型方块：放行到原 `ArcSlopeWandItem`。

因为模型方块本身是 BlockEntity，仅对原物品内部的 `hasBlockEntity()` 判断做一个单点例外；其后的原代码全部继续执行。

## G 键

不再通过修改旧 R 键常量来“假装 G”。

`SurfaceRefineClient` 现在独立向 Fabric 注册 `GLFW_KEY_G`：

- 手持弧方块连接杖 → `new ArcWandConfigScreen()`
- 手持模型渲染杖 → `new ModelRenderScreen()`

两个界面共用一个真实 G KeyBinding，因此不存在两个 G KeyBinding 抢事件的问题。

## 纯白模型弧形状

未套模型时，弧段仍调用 0.9.23 的 `UnifiedSurfaceArcRenderer`，使用原 ArcRibbon prism 几何。

只有模型渲染杖真正把 `sourceState` 替换为捕获模型后，才切换 `ModelArcRenderer`。

因此“模型方块连接后先变成重复立方模型/奇怪钩形”的 0.9.24.1 渲染路径已移除。
