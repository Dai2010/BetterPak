# BetterPak
一个方便，快速的压缩包管理器

## 当前进度

- 首轮接入 ZIP 浏览、解压、创建、文本/图片/音视频预览，以及 RAR/RAR5 和 7z 浏览、解压、预览。
- 主页区分创建、解压和预览；设置页提供跟随系统、浅色、深色和自定义 Material You 主题色。
- 创建页和解压/预览页提供高级选项，包括密码、算法、压缩级别、线程、覆盖策略和安全限制；ZIP 创建支持 Deflate/仅存储，暂不支持 ZIP 加密。
- 长任务支持进度、取消、临时文件替换和失败清理；创建页可选择文件或目录，预览页支持文本和图片安全预览。
- 本机不要求 Android SDK；Release workflow 通过 `.github/workflows/release.yml` 在 GitHub Actions 执行 `test`、`lintRelease`、APK/AAB 构建和签名校验，并归档 APK、AAB、R8 `mapping.txt`、SHA-256 和运行元数据。
- 公开 ZIP、RAR4/RAR5 和 7z 样本验收可运行 `./scripts/format-sample-acceptance.sh`；测试样本只在临时目录中使用，网络受限时可用 `BETTERPAK_SAMPLE_DIRECTORY` 或显式运行 `BETTERPAK_SKIP_PUBLIC_SAMPLES=1` 的本地检查。

Release workflow 在远程使用 GitHub Secrets 中的 PKCS#12 发布密钥签名 APK，不把密钥写入仓库。

详细的功能路线、许可证边界和签名流程见 [PLAN.md](PLAN.md)。
