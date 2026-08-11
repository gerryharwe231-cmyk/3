# 0.9.33 Endpoint Trim / Railing Cap / Z Range / Dense-Arc Performance

本版基于用户上传的 0.9.32 源码继续修复，不改变 0.9.23 原连接杖两点/三点/G面板/宽厚/视角定向等核心操作。

## 1. 首尾端点加宽/加厚区域也参与最终自动裁切

0.9.32 的最终裁切只把 `ArcStationFrames` 中的弧段 Prism 当作 cutter。首尾 ModelEndpoint 自己向侧面/上下重复出来的 tile 不属于弧段，因此旁边普通方块不会被切，碰撞也会直接压在端点扩展模型里面。

0.9.33 在 `FinalModelArcTrim` 新增端点 cutter：

- 读取 `endpointLateralAxis / endpointVerticalAxis`；
- 读取端点最终 `lateralSpan / verticalSpan`；
- 按一格一格 endpoint tile 生成真实有向棱柱；
- 起点、终点都写入同一个 `cuttersByCell`；
- 端点中央 ModelEndpoint 自己仍排除，不会把端点块切掉；
- 周围普通方块按真实交集裁切；
- `ARC_TRIM` 碰撞继续保存“剩余普通方块 + 端点/弧体 cutter”的并集。

所以加宽、加厚或者两者同时开启时，首尾周围的普通方块不再漏裁，也不会保留与端点扩展区重叠的原方块碰撞。

## 2. 铁栏杆首尾一侧可见的纸片

中间重复模块的 q=0/q=1 端盖在0.9.32已经剔除，但 ModelEndpoint 本身朝向弧线的那张内部端盖仍然由端点 BER 渲染。

因为它只有一个正面，所以会出现“右侧视角看得到，正面/左侧看不到”的薄片。

0.9.33 对以下连接型端点：

- Fence
- Pane / 铁栏杆
- Wall
- Conquest railing / balustrade 等 ConnectionStateHelper 支持类型

会检查 endpoint BakedQuad 是否完全位于 `arcDirection` 对应的连接平面。若是朝弧线的内部端盖，直接不渲染；外端面和真正连接臂仍保留。

## 3. 密集弧性能继续优化

### 3.1 白色模板也改成单 render leader

此前只有模型替换后的整组弧是单 leader；纯白模板阶段仍然每一个 ArcRibbon BE 都进入 BER。

0.9.33 在 `ArcBuildGroupContext.finishAndTag()` 阶段就选出中央 render leader，并写入 leader marker。

客户端：

```text
一个逻辑弧组
    ↓
只有 render leader 进入 BER
    ↓
leader 一次绘制整组 white template strips
```

其它同组 ArcRibbon followers 在 `isInRenderDistance()` 阶段直接返回 false。

### 3.2 白色模板光照缓存

leader 一次绘制整组时，同一 owner + 同一面方向的光照只采样一次，不再每个四边形都重新查世界光照。

### 3.3 缓存校验 O(1)

`ModelTemplateArcRenderer` 和 `ModelArcRenderer` 不再为了确认缓存有效性，每帧遍历整组所有 BlockEntity。

现在只校验当前 render leader 自己的：

- `renderRevision`
- `sourceState`

组几何发生真正更新时 leader 也会更新，之后才重新编译整组网格。

## 4. Z轴连接/发现范围

原0.9.23 `ArcSlopeWandItem.AxisMapping` 本身已经包含 X/Y/Z 三轴坐标，本版不重写原圆弧数学，避免破坏此前稳定的两点/三点逻辑。

0.9.33 补的是模型弧后处理此前偏小的 Z 方向连接发现范围：

```text
普通组发现：X/Y ±3，Z ±6
端点模型发现：X/Y ±3，Z ±8
端点附近初始弧搜索：Z ±8
```

这让沿 Z 方向展开/偏移的弧组在模型替换、端点绑定、最终裁切和碰撞重建阶段都能被完整识别。

之前已经要求的：

```text
当前面 = 上/下 + 两个端点同高 → 连接失败
```

仍然保留，不会为了增加 Z 范围把这个旧问题重新打开。

## 5. 保持不变

继续保留：

- 两点 / 三点
- 自动轴对称 / 第二点定圆和弧度
- 当前面 / 内弧朝向
- 两点正反弧向
- 自动弧边裁切
- 上下厚度
- 侧面宽度
- G面板
- 视角定向放置
- 模型渲染杖和预览
- 楼梯 / 半砖状态优先级
- Fence / Pane / Wall / Conquest连接状态优先级
- 逻辑弧组
- 扩展碰撞代理
- captured-model 单 render leader

## 6. 构建

GitHub Actions：

```text
Build 0.9.33 Endpoint Trim Railing Z Performance
```

测试时请重新生成旧弧和裁切块；旧世界已经生成的裁切结果不会自动拥有新的 endpoint cutter 数据。
