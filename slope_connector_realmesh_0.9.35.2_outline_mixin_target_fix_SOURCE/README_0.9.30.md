# 0.9.30 Endpoint Visibility / Performance Fix

本版基于用户上传的 `0.9.29.2 LinkedHashMap Build Fix` 源码，只针对本轮明确反馈的问题继续修复；不修改 GitHub，不删除 0.9.23 原连接杖已有功能。

## 1. 终点透明但有实体 / 加宽加厚后终点左右上下缺块

根因不只是渲染器：宽/厚弧生成后，一个完整截面会被世界方块网格裁成多个 Prism。0.9.29.2 为了提取中心链，同一纵向段只选择其中一个代表 Prism，导致其余横向/纵向裁片被丢掉。

0.9.30 改为：

- 同一纵向段的所有裁片先分组；
- 计算同组的完整 width/radial 包围范围；
- 再合并成一个完整中心链截面；
- 端点 metadata、共享 Station、白色模板和模型替换都读取这个完整截面。

因此 3 格、4 格等加宽/加厚时，端点不再只得到中间一格的 span。

另外，连接完成时会立即根据真实首段/末段切线写入两个端点 ModelBlock 的 `arcDirection`，不再等到模型渲染杖替换以后才写方向。共享 Station 的中心也会直接吸附到端点真实连接面。

## 2. UP/DOWN + 两端同高直接拒绝

如果：

- G 面板“当前面”为 `上` 或 `下`；
- 起点和终点 Y 完全相同；

则连接器在进入原生成器之前直接返回失败，不再生成任何弧方块、碰撞代理或异常扇形：

```text
当前面为上/下且两个端点同高：连接失败，请改用侧向面或改变端点高度。
```

## 3. G 面板排版

右侧现在明确分成两组相同格式：

```text
上下 -              上下 +
        当前厚度：N

侧面 -              侧面 +
        侧面宽度：N
```

“侧面宽度”不再用三个按钮硬塞一行。

左侧“放置朝向”标题和按钮整体移动到自动裁切按钮下面，避免文字上半部分压到其它按钮。

## 4. 普通模型方块性能

以前每一个未连接的 ModelBlock 都是 BlockEntity，并且每帧进入 `ModelBlockRenderer` 手工读取 BakedQuad。模型方块越密集，BER 调用越多。

现在：

- 普通未连接、未渲染的 ModelBlock 使用普通区块 MODEL 渲染；
- BER 对这种方块直接退出；
- `isInRenderDistance` 对这种普通 ModelBlock 直接返回 false；
- 只有真正弧端点 / skinned 端点进入自定义端点渲染。

## 5. 碰撞代理性能

加宽/加厚产生的跨格碰撞代理仍然保留实体碰撞，但现在：

- 纯碰撞代理在 ArcRibbon 渲染入口第一步直接取消，不进入组件发现/模型编译；
- 创建/清理代理使用客户端同步而不是整圈邻居物理更新，减少连接瞬间的更新风暴。

## 6. 白色模板与模型替换渲染缓存

之前几个高开销路径会周期性重新扫描/重新编译整条弧：

- 白色 ModelTemplate：20 tick TTL；
- UnifiedSurface atlas：短 TTL / 全成员验证；
- ModelArc mesh：每个 BlockEntity 渲染时遍历整个组件 revision；
- 模型小三角：每帧大量重复查询世界光照。

0.9.30 改为：

- 白色模板按当前 ArcRibbon revision O(1) 命中；
- ModelArc/UnifiedSurface 每个成员只比较自己的 revision/source 快照；
- 组件真的变化时才重新编译；
- ModelArc 每个承载块每帧按 6 个方向缓存光照，不再每一个细分三角单独查世界光照。

## 7. 保持不变

继续保留之前已有的：

- 两点 / 三点；
- 自动轴对称 / 第二点确定弧；
- 当前面 / 内弧朝向；
- 两点正向 / 反向；
- 自动弧边裁切；
- 上下厚度；
- 侧面宽度；
- 清空连接点；
- G 面板；
- 视角定向放置；
- 模型方块；
- 模型渲染杖；
- 模型预览；
- 楼梯 / 半砖状态优先级；
- Conquest Fence / Pane / Wall / Balustrade / Railings 兼容路径。

## 8. 验证

本地静态回归已覆盖 0.9.23～0.9.29.2 的旧测试，并新增：

- 完整截面合并；
- 首尾真实 tangent metadata；
- 终点共享 Station 吸附；
- 普通 ModelBlock 跳过 BER；
- 碰撞代理跳过渲染；
- UP/DOWN 同高拒绝；
- 右侧两行 UI；
- 放置朝向布局；
- 模型/atlas/template 缓存热点。

当前容器没有完整 Minecraft/Fabric Loom 依赖环境，因此最终 `clean build` 仍需要包内 GitHub Actions 或正常 Fabric 开发环境执行。

工作流名称：

```text
Build 0.9.30 Endpoint Visibility Performance
```
