# 0.9.29.2 LinkedHashMap Build Fix

本版只修复 0.9.29.1 GitHub Actions `compileJava` 暴露出的缺失 import：

```java
import java.util.LinkedHashMap;
```

`UnifiedSurfaceArcRenderer` 中两处代码使用了 `new LinkedHashMap<>()`：

- `memberRevisions`
- `groups`

0.9.29.1 漏掉了 import，导致 Gradle 在 `compileJava` 阶段报 `cannot find symbol: class LinkedHashMap`。

0.9.29.2 已补齐 import，并新增：

```text
tests/test_09292_linkedhashmap_import.py
```

用于保证源码中只要继续使用 `LinkedHashMap`，对应 import 必须存在。

其它 0.9.29.1 / 0.9.29 功能逻辑不改。
