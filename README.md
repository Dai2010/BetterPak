# BetterPak
一个方便，快速的压缩包管理器

## 当前进度

- 首轮接入 ZIP 浏览、解压、创建、文本/图片/音视频预览，以及 RAR/RAR5、7z、TAR、Zstandard `.zst` 和 TAR+Zstandard `.tar.zst` 浏览、解压、预览。
- 主页区分创建、解压和预览；设置页提供跟随系统、浅色、深色和自定义 Material You 主题色。
- 创建页和解压/预览页提供高级选项，包括密码、算法、压缩级别、线程、覆盖策略和安全限制；ZIP 创建支持 Deflate/仅存储，暂不支持 ZIP 加密。
- 长任务支持进度、取消、临时文件替换和失败清理；创建页可选择文件或目录，预览页支持文本和图片安全预览。
- 预览条目采用文件管理器式列表和文件图标，默认隐藏复选框，长按后进入选择模式；每个文件使用独立详情页，底部提供“解压到…”操作。
- 音频和视频详情页提供播放/暂停、进度拖动和倍速控制；无法安全预览或播放器不支持的条目会解压到应用内部 `Download/BetterPak` 目录并尝试自动打开，临时文件在任务结束后清理。
- 本机不要求 Android SDK；Release workflow 通过 `.github/workflows/release.yml` 在 GitHub Actions 执行 `test`、`lintRelease`、四种 ABI APK 构建和签名校验，并归档 APK、SHA-256 和运行元数据。
- 公开 ZIP、RAR4/RAR5 和 7z 样本以及本地 TAR/Zstandard 互操作验收可运行 `./scripts/format-sample-acceptance.sh`；测试样本只在临时目录中使用，网络受限时可用 `BETTERPAK_SAMPLE_DIRECTORY` 或显式运行 `BETTERPAK_SKIP_PUBLIC_SAMPLES=1` 的本地检查。

Release workflow 在远程使用 GitHub Secrets 中的 PKCS#12 发布密钥签名 APK，不把密钥写入仓库。

详细的 v0.0.5 格式验收、依赖审计和签名流程见 [格式验收](docs/v0.0.5-format-acceptance.md) 和 [依赖审计](docs/dependency-audit-v0.0.5.md)；当前执行顺序以工作区根目录的 `NEXT_PLAN.md` 为准。
