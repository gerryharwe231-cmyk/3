# 0.9.35 Physical Landings / Real Trim / Performance

本版直接基于用户上传的 0.9.34.1 源码继续修复，并保留 0.9.34.1 已完成的 Mixin package 隔离启动修复。

## 1. 高度/Z轴两点弧：两端改成真实水平 landing

0.9.34 的 quintic 曲线只有端点那个采样斜率严格为0；紧挨端点的下一段已经开始抬升，所以宽厚模型实际看起来仍会斜着进入端点。

0.9.35 改成：

```text
真实水平起步段 → C2 quintic 平滑起坡/收坡 → 真实水平结束段
```

正常长度时首尾各保留约0.5～1格真正水平段；短距离会自动缩短 landing，保证中间仍有坡段。
中部坡度仍由高度差 / 可用水平距离决定：距离越近且高差越大越陡，距离越远越缓。

同时删除 0.9.34 中 `settings.arcSide != riseSide` 时回退旧路径的条件。现在只要是：两点模式、当前面=上/下、两个端点不同高，就强制走平滑高度坡，不再随机掉回0.9.17的“直线→四分之一圆→竖直”。

## 2. 自动弧边裁切恢复为真正 Boolean 差集

0.9.34 虽然做了差集，但又把弧体积并进 ARC_TRIM 的碰撞，测试时表现为像两个碰撞体叠在一起。

0.9.35 中 ARC_TRIM 只保存裁切后真正剩余的普通方块体积，不再把弧体塞进 TrimBox。

## 3. 裁切视觉重新三角化

差集后的全部 Poly 都重新生成可见三角形：

- 保留真实外露原表面；
- 保留斜向切面；
- 支持楼梯、上/下半砖和多箱体 VoxelShape；
- subtraction 产生的内部共享面会剔除；
- cutter plane 上的三角形会标记为新切面。

因此自动裁切不再只是改碰撞而视觉仍保持完整方块。

## 4. 不可见 collision proxy 不再能被准星打掉

collision proxy 继续保留物理碰撞，但 outline/raycast 变为空。玩家不会再优先打到一个不可见代理，从而出现“碰撞没了、视觉弧还留着”的假拆除。

## 5. 打掉真实弧成员后强制刷新整组视觉

单 render-leader 优化以前只检查 leader revision。若玩家打掉 follower，leader没变，旧缓存可能继续绘制已不存在的段。

0.9.35 新增 group break lifecycle：真实 ArcRibbon 成员被移除时，整组幸存成员重新选择 render leader、刷新 revision，下一帧重新编译模型/白模板缓存，删除段不会继续残留。

## 6. 密集宽厚弧继续降负载

- 模型弧与白模板 owner 提交范围：64 → 48格；
- >=4 横截面tile：至少1/4格纵向切片；
- >=9 tile：至少1/2格纵向切片；
- >=16 tile：每格只保留一个纵向模型段；
- 保留此前单leader、chunk bucket、PreparedQuad、普通ModelBlock非BE等优化。

这些优化会继续降低模组自身CPU/GPU提交量，但不能承诺任何机器、任何光影与整套模组环境都绝对锁定60FPS。Iris/光影、视距、Conquest模型复杂度以及同时可见弧数量仍会影响最终帧率。

## 7. 保持不变

继续保留：两点/三点、自动轴对称、当前面/内弧朝向、正向/反向、自动弧边裁切、上下厚度、侧面宽度、G面板、视角定向放置、模型渲染杖、楼梯/半砖优先级、Fence/Pane/Wall/Conquest连接优先级、上/下同高拒绝生成，以及0.9.34.1启动Mixin package隔离修复。

## 8. 验证

实际Java harness会检查：起点连续多个样本真正同高、终点前连续多个样本真正同高、landing样本路径切线水平、中段确实有坡度、累计距离单调递增。

GitHub Actions工作流：

```text
Build 0.9.35 Physical Landings Real Trim Performance
```
