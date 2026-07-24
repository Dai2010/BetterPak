# 格式样本验收

## 范围

验收脚本使用 `libarchive` 官方测试数据，覆盖：

- ZIP、RAR4、RAR5 和 7z 的浏览基准。
- 中文文件名、目录、空目录、空文件和零字节文件。
- RAR4、RAR5、7z 密码包的正确密码、错误密码和固实包。
- ZIP、RAR5 和 7z 损坏包的失败处理。
- 本地生成 ZIP/7z 后由 `unzip` 与 `7z` 互相校验；公开 RAR 样本由 `unrar` 验证。

样本只包含上游公开测试内容；下载的 `.uu` 文件、解码后的归档、生成归档和损坏副本都位于 `mktemp` 创建的临时目录，脚本退出时自动删除。

## 重复执行

需要 `curl`、`python3`、`unzip`、`zip`、`7z`、`unrar`、`truncate` 和 `sha256sum`：

```sh
./scripts/format-sample-acceptance.sh
```

脚本将上游样本固定到 `libarchive` 提交 `f5509ae993ac30417f81acc5118f232ae3f2d27d`，并校验解码后归档的 SHA-256：

| 样本 | SHA-256 |
| --- | --- |
| `test_compat_zip_1.zip` | `d593e51c7167de3a61ee060b1752d416038014d6e325a260c5529c65262c3d01` |
| `test_read_format_rar_unicode.rar` | `3dc3d2a8f3f6cbfeeb6f45b3bb09fa7b2bf4b52e10e80df29217fdeaa9dbda45` |
| `test_read_format_rar4_solid_encrypted.rar` | `428d44a21042069bbc1891c30a66c48f55540aa37dd8b7ff92c39c5914e3a4a6` |
| `test_read_format_rar5_unicode.rar` | `062c77fb1d47efbd5a468609a9c283ecb0b4cae43b0f6778935c41a975cedee9` |
| `test_read_format_rar5_solid_encrypted.rar` | `307de6b76f6b7cc3a221975e14b8a6d7a6b87a30fd1ee7a0e08e4333724e5657` |
| `test_read_format_7zip_copy.7z` | `67a85f85950c4aaa524eb3f22be1fbac586e90c76de21e10fc1c2ce7685e04c2` |
| `test_read_format_7zip_encryption.7z` | `91f5427859ad1391c9fb877c98e4b55213c479f39fd012882f7d112388842076` |

如果已经准备好与表格同名的解码归档，可以通过 `BETTERPAK_SAMPLE_DIRECTORY` 指向样本目录；脚本会跳过下载并继续校验哈希和工具行为：

```sh
BETTERPAK_SAMPLE_DIRECTORY=/path/to/samples ./scripts/format-sample-acceptance.sh
```

也可以使用保持相同目录结构的镜像覆盖上游地址：

```sh
BETTERPAK_LIBARCHIVE_BASE=https://mirror.example/libarchive/test \
  ./scripts/format-sample-acceptance.sh
```

如果网络不可用且没有公开样本，可显式只运行本地生成 ZIP/7z、密码、Unicode 路径和损坏 ZIP 检查。此模式不会执行公开 RAR/RAR5/7z 样本或损坏 RAR/7z 检查，输出也会明确标注为 local checks：

```sh
BETTERPAK_SKIP_PUBLIC_SAMPLES=1 ./scripts/format-sample-acceptance.sh
```

上游的非固实 RAR 样本包含不同条目的不同密码，脚本不把它当作“一次输入密码即可解开整个包”的样本；这避免把预期的单密码能力边界误报为回归失败。

## 已有验收记录

2026-07-24 UTC 通过 `ghfast.top` 代理在 Termux 中执行脚本，28 项检查全部通过，包含样本哈希、Unicode 路径、密码成功/失败、跨工具创建校验和损坏包拒绝。下载、解码、生成和损坏样本均已在脚本退出时删除。

此前直接连接 `raw.githubusercontent.com` 曾因网络超时停止；脚本现会报告具体失败样本，并支持使用 `BETTERPAK_LIBARCHIVE_BASE` 指向代理/镜像或使用预先下载的样本目录复核。

## 本次环境限制

该验收脚本验证桌面归档工具与公开样本的基准行为；当前 Termux 环境没有 Java、Gradle 或 Android SDK，因此不能在本机执行 `BetterPak` 的 Android 单元测试或 APK 互操作实测。应在具备 Android SDK 的 CI/开发机上继续执行 `lintRelease`、`test` 和真机验收。
