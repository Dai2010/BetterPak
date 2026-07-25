# 第三方组件记录

BetterPak 本身使用 GPLv3。正式发布前需要将对应许可证文本随发布物归档，并确认所有依赖与分发方式兼容。

| 组件 | 版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| AndroidX Activity / Compose / Material 3 / Navigation / DataStore | 由 Gradle 配置锁定 | Android 界面、导航和设置存储 | Apache-2.0 |
| Kotlin Coroutines | 1.9.0 | 后台任务和取消 | Apache-2.0 |
| Apache Commons Compress | 1.27.1 | 7z、TAR 容器读写和 Zstandard 流适配 | Apache-2.0 |
| zstd-jni | 1.5.7-11 | Zstandard `.zst` / `.tar.zst` 流读写；Maven Central；无额外运行时传递依赖 | BSD-2-Clause |
| XZ for Java | 1.10 | LZMA2 编解码 | Public Domain / 0BSD |
| Junrar | 7.5.5 | RAR/RAR5 读取和解压 | LGPL-2.1-or-later |

zstd-jni 的 native ABI 和 APK 体积影响由 CI Release 元数据复核，记录见 `docs/dependency-audit-v0.0.5.md`。版本升级时需要重新核对许可证、传递依赖和漏洞记录。RAR 创建不包含在当前版本，因为编码器授权边界尚未满足发布要求。
