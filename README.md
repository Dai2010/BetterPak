# BetterPak
一个方便，快速的压缩包管理器

## 当前进度（v0.0.10）

- 首轮接入 ZIP 浏览、解压、创建、文本/图片/音视频预览，以及 RAR/RAR5、7z、TAR、Zstandard `.zst` 和 TAR+Zstandard `.tar.zst` 浏览、解压、预览。
- 主页区分创建、解压和预览；设置页提供跟随系统、浅色、深色和自定义 Material You 主题色。
- 创建页和解压/预览页提供高级选项，包括密码、算法、压缩级别、线程、覆盖策略和安全限制；ZIP 创建支持 Deflate/仅存储，暂不支持 ZIP 加密。
- 长任务支持进度、取消、临时文件替换和失败清理；创建页可选择文件或目录，预览页支持文本和图片安全预览。
- 预览条目采用文件管理器式列表和文件图标，默认隐藏复选框，长按后进入选择模式；每个文件使用独立详情页，底部提供“解压到…”操作。
- 音频和视频详情页提供播放/暂停、进度拖动和倍速控制；无法安全预览或播放器不支持的条目不会自动打开，用户明确点击后才会受限解压到缓存，并通过 `FileProvider` 和 `Intent.createChooser()` 交给系统选择应用。
- PDF、DOC/DOCX、XLS/XLSX、PPT/PPTX、ODF 文档不在 APK 内置办公套件；BetterPak 不执行宏、脚本或嵌套归档，没有外部处理器时仍可选择安全解压。
- 归档引擎通过领域 `ArchiveEngine` 边界提供识别、列表、预览、创建、解压、取消和进度；非法 ZIP/RAR/7z/TAR 路径不再静默跳过，错误会分类为安全、限制、权限、密码或损坏等类型。
- `v0.0.10` 新增独立的 Kotlin/JVM `TarArchiveCore`；TAR/TAR.Zstandard 的列表、创建、事务式解压、选定条目解压、受限单条目读取和流式输出已迁入核心，RAR/7z/独立 Zstandard 继续按 `NEXT_PLAN.md` 分阶段迁移。
- `v0.0.9-fix1` 发布候选已将 Android ZIP 列表、创建、解压和受限预览接到 `archive-core`；SAF `Uri` 仍只在 Android 适配层转换为临时本地文件，ZIP 核心错误会映射回 Android 领域错误。发布是否成立以 Release workflow 的测试、Lint、签名构建和产物检查为准，当前不宣称桌面支持。
- `v0.0.9-fix2` 修复 SAF 单文件 `/document/...` URI 创建失败：按 `DocumentsContract.isTreeUri()` 分流目录树和单文件，分别使用 `fromTreeUri()`、`fromSingleUri()`，统一复制到临时 staging；权限撤销、无效 URI、空文档和读取失败会显示中文可操作提示。该修复不新增 `MANAGE_EXTERNAL_STORAGE`，目录递归、进度、取消和失败清理保持不变。
- 创建和完整解压会保存来源 URI、目标 URI、格式、状态、错误类型、进度摘要和时间，主页通过 ViewModel 观察任务；密码不进入任务记录、设置、日志或通知。
- 设置页可保存最大条目数、最大展开体积、最大预览大小和覆盖策略；默认限制仍为 100000 条目、50 GiB 展开体积和 8 MiB 预览。
- v0.0.8 移除 OneDrive/Google Drive 云盘入口、OAuth、Token 存储和网络 provider，避免发布无法完成登录配置的功能；原因和范围见 [v0.0.8 更新说明](docs/v0.0.8-release-notes.md)。
- 本机不要求 Android SDK；Release workflow 通过 `.github/workflows/release.yml` 执行 `:archive-core:test`、`test`、`lintRelease`、四种 ABI APK 构建和签名校验，并归档 APK、SHA-256 和运行元数据。
- 公开 ZIP、RAR4/RAR5 和 7z 样本以及本地 TAR/Zstandard 互操作验收可运行 `./scripts/format-sample-acceptance.sh`；测试样本只在临时目录中使用，网络受限时可用 `BETTERPAK_SAMPLE_DIRECTORY` 或显式运行 `BETTERPAK_SKIP_PUBLIC_SAMPLES=1` 的本地检查。

Release workflow 在远程使用 GitHub Secrets 中的 PKCS#12 发布密钥签名 APK，不把密钥写入仓库。所以不要尝试在仓库里找密钥。

详细的 v0.0.5 格式验收、v0.0.6 实现记录、v0.0.8/v0.0.9/v0.0.9-fix1/v0.0.9-fix2/v0.0.10 更新说明、`archive-core` Spike、核心迁移、依赖审计和签名流程见 [格式验收](docs/v0.0.5-format-acceptance.md)、[v0.0.6 实现记录](docs/v0.0.6-implementation.md)、[v0.0.8 更新说明](docs/v0.0.8-release-notes.md)、[v0.0.9 更新说明](docs/v0.0.9-release-notes.md)、[v0.0.9-fix1 更新说明](docs/v0.0.9-fix1-release-notes.md)、[v0.0.9-fix2 更新说明](docs/v0.0.9-fix2-release-notes.md)、[v0.0.10 更新说明](docs/v0.0.10-release-notes.md)、[v0.0.8 `archive-core` Spike](docs/v0.0.8-archive-core-spike.md)、[v0.0.10 核心迁移](docs/v0.0.10-archive-core-migration.md) 和 [依赖审计](docs/dependency-audit-v0.0.5.md)；当前执行顺序以工作区根目录的 `NEXT_PLAN.md` 为准。

## v0.0.8 更新说明

- 暂时移除 OneDrive/Google Drive 云盘功能及其 OAuth 实现。
- 原因是当前个人 Microsoft 账号无法在 Microsoft Entra 目录外完成应用注册，无法为普通用户提供稳定可用的登录流程；Google Drive 也不适合作为国内使用环境的发布依赖。
