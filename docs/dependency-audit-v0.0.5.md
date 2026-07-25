# v0.0.5 依赖审计

## Zstandard

| 项目 | 记录 |
| --- | --- |
| 组件 | `com.github.luben:zstd-jni` |
| 版本 | `1.5.7-11` |
| 来源 | Maven Central |
| 用途 | `.zst` 单流和 `.tar.zst` 的 Zstandard 流层 |
| 许可证 | BSD-2-Clause（含上游 Zstandard 许可说明） |
| 直接传递依赖 | Gradle 依赖图中无额外运行时归档引擎；以 CI `dependencies` 输出复核 |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86`，另生成 universal APK；由 APK 解包和 Release 元数据复核 |
| 体积影响 | 不在 Termux 中估算；CI 为每个 ABI 记录最终 APK 字节数，避免把桌面 JAR 大小当作 APK 结果 |

复核命令：

```sh
gradle --no-daemon --max-workers=1 :app:dependencies --configuration releaseRuntimeClasspath
unzip -l app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep -E 'lib/(arm64-v8a|armeabi-v7a|x86)/.*(zstd|libzstd)'
sha256sum app/build/outputs/apk/release/app-*-release.apk
```

## 许可证边界

- `commons-compress` 负责 TAR 容器层，许可证和版本记录在 `THIRD_PARTY_LICENSES.md`。
- `zstd-jni` 负责流层；不会用于实现 RAR 创建。
- 正式发布前应保存 Gradle 依赖树、许可证文本、漏洞扫描结果和四种 APK 的 SHA-256。
