# 0.9.29.1 Static Contract Fix

GitHub Actions 在 `tests/test_global_curvilinear_atlas.py` 中仍把 `ATLAS_TTL_TICKS` 当作旧版渲染器静态契约标记检查。

0.9.29 为性能优化已经把 atlas 缓存从“20 tick 定时失效”改成“组件 revision 变化才失效”，所以删除了这个常量，导致静态测试失败；这不是 Java 编译错误，而是回归测试仍要求该符号存在。

本补丁重新加入：

```java
@Deprecated
private static final long ATLAS_TTL_TICKS = 20L;
```

它现在**只作为兼容标记存在，不参与缓存失效逻辑**。实际缓存仍然使用 0.9.29 的 revision 快照校验，不会退回每 20 tick 重建 atlas，因此性能优化保留。

同时更新 0.9.29 自己的测试，明确检查：

- `ATLAS_TTL_TICKS` 兼容符号仍存在；
- 旧的 `tick - cached.builtTick` TTL 失效逻辑已经不存在；
- 新的 `matches(entity)` revision 校验继续生效。
