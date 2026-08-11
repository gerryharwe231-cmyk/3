# 0.9.32 Final Trim / Endpoint Priority / Railing Caps

本版基于用户上传的 0.9.31 源码，只处理本轮四类问题，不改 0.9.23 原连接杖的两点/三点、G面板、宽厚、弧向、视角定向等其它功能。

## 1. 自动弧边裁切改为“最终几何后裁切”

旧核心的自动裁切发生在最终加宽/加厚之前，而且只接受普通完整方块。因此最终弧重新变宽/变厚后会再次伸入已经裁过的方块；楼梯、半砖则完全不会进入旧裁切。

0.9.32 对 ModelBlock 弧：

1. 只对当前生成调用屏蔽旧的预裁切；不会改全局开关。
2. 原弧生成、上下厚度/侧面宽度、共享截面全部完成。
3. 首尾端点 metadata 完成。
4. 使用最终 `ArcStationFrames` 重新生成真实 cutter。
5. 再执行最终裁切。
6. 最后生成跨格碰撞代理。

所以自动裁切看到的是最终宽度和最终厚度，而不是1格初始弧。

### 楼梯/半砖裁切

新增 `FinalModelArcTrim` 不再要求 `isOrdinaryFull`。

它读取目标方块真实：

- `outlineShape`
- `collisionShape`
- `VoxelShape.getBoundingBoxes()`

楼梯、上/下半砖和其它非BlockEntity的多箱体模型都会先转换为实际凸体，再和最终弧体做几何差集。

### 裁切格里的碰撞

同一个坐标不能同时放 `ARC_TRIM` 和 `ARC_RIBBON` collision proxy。
因此新的 TrimBlockEntity collision 保存：

```text
裁切后剩余普通方块体积
+
该格内部最终弧体积
```

加宽/加厚后的弧即使穿过裁切块坐标，也不会只看得到但没有实体。

## 2. 模型状态优先级固定

端点规则继续严格按：

```text
楼梯 / 半砖完整捕获状态
        ↓
栏杆 / Fence / Pane / Wall / Conquest连接状态
        ↓
弧端点纹理与模型连续方向
        ↓
玩家视角定向
        ↓
Minecraft默认放置机制
```

- 楼梯/半砖：保留捕获的 facing、top/bottom、half 等状态；只把邻居自动造成的 stair corner shape 规范为 STRAIGHT。
- 连接型栏杆：先走真实邻居连接状态，不允许普通纹理规则覆盖。
- 普通方块：终点 seamDirection 仍高于玩家视角和MC默认朝向。

### 首尾和中段不再各自判断截面方向

中段只在整条弧中部计算一次 `ArcCrossSectionMapping`。
这套 mapping 会写入两个 ModelEndpointBlock 的 NBT。
端点渲染器只读取这套 mapping，不再在首尾重新 `resolve()`。

这样 terminal endpoint 不会因为局部 width/radial 符号不同再次翻面，普通纹理和楼梯局部模型都与中间弧使用同一个截面基准。

## 3. 铁栏杆周期性薄片

Fence/Pane/Wall/iron-bar 类型以前为了保留局部边界面，没有剔除重复模块的纵向端盖。
每一个1格重复模块的 q=0/q=1 端盖会留在内部，因此表现成固定间隔的纸片。

0.9.32 改为：

- Fence / Pane / Wall / Conquest connected profile：剔除**内部纵向端盖**；
- 仍保留其 lateral/vertical 局部边界面；
- 楼梯/半砖也继续保留局部不完整边界面；
- 普通完整方块仍可正常做全部内部tile面剔除。

因此只消除栏杆之间不该存在的薄片，不会重新引入楼梯加宽透明洞。

## 4. 保持的性能架构

继续保留0.9.31：

- 普通未连接 ModelBlock 是真正普通 Block；
- 只有首尾端点转换成 ModelEndpointBlock + BlockEntity；
- captured model 弧只由一个 render leader 绘制整组；
- collision proxy 不参与昂贵模型渲染；
- mesh/atlas 使用 revision 快照缓存。

最终裁切只在生成/重建时运行，不是每帧运行。

## 5. 编译

GitHub Actions 工作流：

```text
Build 0.9.32 Final Trim Endpoint Priority Railing Caps
```

旧弧的裁切块、端点mapping和碰撞不会自动升级，测试时请重新生成并重新套模型。
