# 独立 Zstandard `archive-core` 迁移记录

本记录对应 `v0.0.11`，版本元数据为 `versionCode 13`、`versionName 0.0.11`。

## 已完成

- 新增 Kotlin/JVM `ZstandardArchiveCore`，支持独立 `.zst` 单流的展开大小测量、受限字节读取、流式输出和事务式文件解压。
- 核心统一检查最大展开体积、调用方读取上限和取消状态，并输出与 ZIP/TAR 核心一致的进度对象。
- 损坏的 Zstandard 数据映射为 `CORRUPT_ARCHIVE`；输出写入失败映射为权限错误；源归档覆盖、目录冲突和符号链接父路径会被拒绝。
- 事务式文件解压先写入同目录随机 `.part` 文件，再原子替换或普通替换；失败和取消都会删除临时文件。
- Android 独立 `.zst` 的列表、解压、预览、内部存储解压和外部打开已改为调用 `ZstandardArchiveCore`；SAF URI 暂存、`DocumentFile` 覆盖策略和 chooser 仍留在 Android 适配层。
- Android 列表现在记录 `.zst` 单流的压缩大小，独立 Zstandard 的取消和核心进度也通过现有合并通道转发。

## 测试

- 新增单流测量/读取/复制/事务式解压、调用方上限、核心展开上限、损坏流分类、取消清理、源归档覆盖和符号链接父路径测试。
- 当前工作区使用缓存的 Kotlin 2.0.21 编译器和 JUnit 4.13.2 直接编译并运行全部 `archive-core` 测试，结果为 `OK (22 tests)`。
- Termux 无法加载 `zstd-jni` 的 glibc ARM64 原生库，Zstandard 原生闭环测试按与 TAR.Zstandard 既有测试相同的策略跳过；非原生边界测试已执行。
- 当前工作区没有 Android SDK，无法在本机执行 Android 单元测试、Lint 或 APK 构建；完整门禁仍需运行：

```sh
gradle --no-daemon --max-workers=1 :archive-core:test test lintRelease
```

## 后续范围

- 7z 和 RAR/RAR5 尚未迁入 `archive-core`；RAR 创建仍不做。
- Android 真机的 `.zst` SAF 权限撤销、覆盖策略、空间不足、取消清理和 chooser 行为仍需验收。
- 本版本不包含 RAR/RAR5、7z 核心迁移、RAR 创建、桌面平台支持或办公文档内置处理。
