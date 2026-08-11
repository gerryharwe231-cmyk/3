# 0.9.28 Dimension Tiling / Collision / Model-State Priority

本版基于 0.9.27，只处理本轮明确提出的尺寸、碰撞、端点接缝和模型状态优先级，不改 0.9.23 原连接杖的两点/三点/弧向/当前面/G键/视角定向等其它功能。

## 1. 尺寸名称按实际效果调整

保留内部旧字段/旧命令名称以兼容原设置对象，但 G 面板显示改为：

- 原来的 `左右宽度` → **上下厚度**
- 原来的 `上下厚度` → **侧面宽度**

对应按钮：

- `上下 - / 上下 +`
- `侧面 - / 侧面 +`

命令仍兼容旧名字，但反馈文字使用新名称：

```mcfunction
/slopeconnector lrwidth <格数>   # 面板显示：上下厚度
/slopeconnector udwidth <格数>   # 面板显示：侧面宽度
/slopeconnector dimensions
```

## 2. 宽/厚不再拉伸一份纹理或模型

当目标截面是 3 格时，中间弧不会把一份 1 格 BakedModel/UV 拉到 3 格。

0.9.28 会把截面拆成真正的 1 格单元：

```text
3格侧面宽度 = 1格模型 + 1格模型 + 1格模型
3格上下厚度 = 1格模型 + 1格模型 + 1格模型
```

每个单元继续使用源 BakedModel 自己的 UV，因此砖块/棋盘/栏杆等都是按块重复，再在共享截面上连续弯曲。

内部相邻 tile 的接触面会剔除，避免重复面闪烁。

## 3. 扩展出来的体积有真实跨格碰撞

以前视觉几何可以伸出 ArcRibbon holder 所在的一个格子，但 Minecraft 碰撞查询不一定会访问原 holder，导致新增部分看得到却穿过去。

新增 `ArcCollisionProxyBuilder`：

- 按真实 Prism 体积栅格化占用；
- 在额外覆盖的世界格子生成不可见、只碰撞的 ArcRibbon proxy；
- proxy 不参与渲染、材质和拓扑排序；
- 尺寸重新生成时会清理旧 proxy，避免缩小尺寸后残留空气墙；
- 起点/终点的扩展 tile 也会生成跨格碰撞代理。

ModelBlock 自己的碰撞也会按端点 tile 重复。

## 4. 起点/终点同步加宽、加厚

端点不再永远只有一个中心模型。

`ArcEndpointMetadataBinder` 将弧线首尾真实共享截面的：

- 宽度轴
- 厚度轴
- 侧面跨度
- 上下跨度

写入两个 ModelBlock endpoint。

端点渲染器按同样的一格 tile 网格重复模型；内部 tile 面会剔除，避免重叠闪烁。

## 5. 直接端点 → 圆弧

首尾没有直线引导段、端点直接进入圆弧时，不再做每顶点 miter 补洞。

现在起点/终点使用 `ArcStationFrames.alignEndpoint(...)` 将整个首尾 Station 对齐到端点真实连接平面。白色模板、中间捕获模型和端点 metadata 都使用同一个 Station。

所以外弧和内弧不会各用一套近似接头。

## 6. 模型状态优先级

0.9.28 明确为：

```text
楼梯 / 半砖完整捕获状态
        ↓ 最高
栏杆 / Fence / Pane / Wall / Conquest连接状态
        ↓
弧端点纹理/模型连续方向
        ↓
玩家视角定向开关
        ↓
Minecraft默认放置朝向
```

### 楼梯 / 半砖

模型渲染杖保存完整 BlockState。

楼梯保留：

- 四个 `facing`
- `half=top/bottom`
- `shape`
- 其它原状态

半砖保留：

- `type=top/bottom/double`
- 其它原状态

中段和端点都使用捕获到的 BakedModel 本身作为局部模型，不先把 facing 改成弧线方向，因此四个楼梯朝向不会被归一化掉。

### 栏杆 / 连接型模型

连接型模型优先走真实邻居状态：

- Vanilla/Conquest Fence、Pane、Wall：N/E/S/W连接臂
- Conquest Balustrade：axis
- Conquest Railings：facing + open

只有不属于楼梯/半砖且不属于连接型模型时，才进入普通弧端点纹理连续规则。

## 7. 保持不变

继续保留：

- 两点 / 三点
- 自动轴对称 / 第二点定弧度
- 当前面 / 内弧朝向
- 正向 / 反向
- 自动弧边裁切
- 清空连接点
- G 面板
- 视角定向放置
- 模型方块
- 模型渲染杖与模型预览
- 0.9.23 原连接杖流程

## 8. 编译

GitHub Actions 工作流：

```text
Build 0.9.28 Dimension Tiling Collision State Priority
```

旧弧保存的是旧几何/旧碰撞数据，测试0.9.28时请重新生成并重新套模型。
