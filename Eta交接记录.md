# Eta 交接记录

> 这份文件是给「下一个接手的人」看的，不是给用户看的说明书。
> 目标：5 分钟内知道做了什么、在哪、为什么这么做、坑在哪、下一步做什么。

**最后更新**：2026-11-26

---

## 一句话状态

Magpie 网关已集成进 Eta fork，代码完成并推送，CI 构建通过（run `45777063819`，提交 `ea775ad`）。
**第二轮验证**：三处启动根因已修（详见 `docs/Eta改动说明.md` 二版）。
待真机验证：面板能否打开、两个图标是否正常。

---

## 仓库与分支

| 项 | 值 |
|---|---|
| 工作目录 | `/workspace/02-项目projects/Eta`（= `/data/local/tmp/eta/02-项目projects/Eta`） |
| origin | `git@github.com:Mangi-11/Eta.git`（上游，只读同步） |
| fork | `git@github.com:LANDRINKJUICE/Eta.git`（推送目标） |
| 分支 | `feat/magpie-gateway` |
| 基线 | `26e9835`（上游 4.0.0） |
| 关键提交 | `e80065e` 修编译错误 / `ea775ad` 修功能缺陷 / `180d3fc` 加文档 |

其他参考仓库（只读，不改）：
- `/workspace/02-项目projects/magpie` — Magpie 源码，稀疏克隆（只有 `internal/` 和 `cmd/`）
- `/workspace/02-项目projects/magpie.partial` — 早期中断的完整克隆残留，可删

---

## 做了什么

把 Magpie（本地 LLM 网关）作为可选组件接进 Eta：

1. **安装**：Linux 工具环境页多一个「Magpie 网关」条目，用户点了才下载（96 MB）
2. **启动**：会话页菜单「打开 Magpie」，首次确认后启动，之后直接启动
3. **打开**：用系统默认浏览器打开面板（不搬 WebView）
4. **关闭**：运行中才显示，点击需二次确认
5. **Provider**：模型提供商列表内置 `Magpie` 一项，指向本机网关

不改 Magpie 源码，不打包进 APK，不做独立容器。

---

## 关键事实（血泪教训，别再踩）

### 端口

| 端口 | 用途 |
|---|---|
| **3425** | 网关 API（`/v1/chat/completions`），内置 Provider 的 baseUrl 用这个 |
| **3430** | Web 面板，用户打开的是这个 |

**两个端口在同一个进程里**，`serve --web=host:port` 同时提供。

### `serve` 不加 `--web` 就没有面板

```go
// internal/cli/web_cli.go:246
if web == "" { return serve() }   // 只起网关，无面板
```

我最初就是漏了这个，导致「打开不了」。

### 面板要密钥

```go
// internal/gui/web.go
MAGPIE_WEB_KEY  // ≥16 字符，仅 A-Za-z0-9-._~
// 链接格式：scheme://host:port/?k=<key>
```

Eta 里生成 32 位 hex（UUID 去连字符）存 DataStore，启动时用 `MAGPIE_WEB_KEY` 注入，
打开时拼 `?k=`。这样点开直接进面板，不用手输。

### HOME 都是 /root

chroot 和 PRoot **都**以 `HOME=/root` 进入 rootfs（见 `ProotCommandBuilder.kt:41`）。
不要写 `/home/eta` 分支，那是错的。

### PRoot 用 --root-id

`ProotCommandBuilder` 带 `--root-id`，rootfs 内以 uid 0 运行，`/root` 可写。

### 图标

| 用途 | 资源 | 形式 |
|---|---|---|
| 菜单 | `drawable/ic_magpie.xml` | 单色矢量（`GlassPopupItem` 要 `ImageVector`） |
| Provider | `drawable-xxxhdpi/provider_logo_magpie.webp` | 128×128 彩色 |

矢量是用 potrace 从 PNG 描摹的，**路径坐标在 614–1557**，
必须把 viewport 设为 `108.46` + 平移 `(-54.32, 160.63)` 才正常。
直接用 `scale(0.1)` + viewport 256 会小得看不见。

---

## 代码在哪

### 新增

```
agent/terminal/linux/MagpieGatewayManager.kt   启停、就绪探测、面板密钥
.github/workflows/magpie-build.yml             只对 feat/** 触发，出双包
res/drawable/ic_magpie.xml                     菜单单色图标
res/drawable-xxxhdpi/provider_logo_magpie.webp Provider 彩色图标
docs/Eta改动说明.md                     详细设计与实现
```

### 修改（都是加行，只删过 1 行）

```
PinnedLinuxToolInstaller.kt    +87   ManagedLinuxTool.MAGPIE + artifact + 两条激活路径
LinuxPackageProfiles.kt        +23   MAGPIE profile（ALL 列表那行重写，-1）
AlpineEnvironmentPaths.kt       +2   marker + revision
SettingsDataStore.kt           +30   magpiePanelKey / magpieFirstRunConfirmed
Provider.kt                     +1   ProviderSourceTypes.MAGPIE
BuiltinProviders.kt            +10   内置 Provider（sortOrder 12）
ProviderSourceRegistry.kt       +2   knownSourceTypes + ID 映射（**漏了会归一化成 CUSTOM**）
ProviderReasoning.kt            +1   穷尽式 when 补分支（**漏了编译不过**）
AgentAppRoot.kt                +95   状态、动作 effect、确认框
AgentAppShell.kt               +52   菜单项 + 四层参数传递
ProviderBranding.kt             +1   图标映射
LinuxEnvironmentScreen.kt      +15   InstallTarget.MAGPIE + 重装清标记
strings.xml ×3                 +12   三语文案
```

---

## 踩过的坑（按发生顺序）

| # | 问题 | 原因 | 修法 |
|---|---|---|---|
| 1 | 编译失败 | `vectorResource` 导入写成 `graphics.vector` | 应为 `androidx.compose.ui.res` |
| 2 | 编译失败 | `appContext` 未声明 | 用 `context.applicationContext` |
| 3 | 打不开 | 端口用了 3425，且 `serve` 无面板 | 改 3430 + `--web` |
| 3b | 打不开（真凶） | `DetachedTaskSupervisor` 缺 `linuxRootfsPath`，payload 直接返回「Linux rootfs 未配置」 | 照 `AgentLocalTools` 补全构造 |
| 3c | 打不开（真凶） | `LaunchedEffect` 内清空自身 key 会取消协程，启动死在中途 | 改为点击处 `uiScope.launch` |
| 4 | 面板 401 | 缺密钥 | 固定 `MAGPIE_WEB_KEY` + `?k=` |
| 5 | 位置错 | 放在「打开浏览器」后面 | 移到「打开终端」后面 |
| 6 | 图标小 | potrace 坐标未重算 viewport | viewport 108.46 |
| 7 | 图标风格错 | Provider 用了单色矢量 | 换原生彩色 webp |

---

## 环境备忘

### 本机工具链（Debian chroot，在 `/data/data/io.github.mangi.eta/files/terminal/debian/rootfs`）

已装：ruff、mypy、shellcheck、shfmt、binutils、gdb、strace、patchelf、lsof、
zipalign、apksigner、ffmpeg、ImageMagick、pandoc、qpdf、7z、tmux、fzf、bat(batcat)、
potrace、apktool、jadx、baksmali、yq、xmllint、csvkit、exiftool、psql、redis-cli 等。

**没装编译器**（按用户要求，构建走云端）。

### 镜像与身份

- pip → 阿里 PyPI
- npm → npmmirror
- git → `LANDRINKJUICE` / `148413356+LANDRINKJUICE@users.noreply.github.com`
- SSH → `~/.ssh/id_ed25519`，走 `ssh.github.com:443`

### CI

- 四个 Secret 已配：`ETA_RELEASE_KEYSTORE_BASE64` / `_STORE_PASSWORD` / `_KEY_ALIAS` / `_KEY_PASSWORD`
- 别名 `eta`，证书 SHA256 `6cb3cbfc…192d6ea`
- 签名材料备份：`/storage/emulated/0/Download/ETA共享文件夹/eta-signing-backup.zip`
- 拉日志需要 PAT：`/storage/emulated/0/Download/ETA共享文件夹/我的tokens与cookie/github token.md`

**读 CI 日志的可用方法**（MCP 工具参数传不进去，别浪费时间）：

```sh
TOKEN=$(cat "/storage/emulated/0/Download/ETA共享文件夹/我的tokens与cookie/github token.md")
JID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/LANDRINKJUICE/Eta/actions/runs/<RUN_ID>/jobs" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['jobs'][0]['id'])")
curl -sL -H "Authorization: Bearer $TOKEN" \
  "https://api.github.com/repos/LANDRINKJUICE/Eta/actions/jobs/$JID/logs" | grep 'e: file'
```

### 静态检查的局限

**本地没有 Kotlin 编译器**，只能做括号平衡、引用计数这类文本检查。
真正的编译错误只能靠 CI 暴露。前两次失败都是这么发现的。

---

## 下一步（如果继续做）

1. **真机验证**（未做）：装 APK → Linux 页装 Magpie → 菜单打开 → 面板能否进
2. **未验证的风险点**：
   - Magpie 自更新后二进制路径是否变化（若变化，`magpiePath()` 会失效）
   - 3430 被占用时的表现（当前是就绪超时，无明确提示）
   - 首次下载 96 MB 的断点续传（`VerifiedArtifactDownloader` 是否支持）
3. **待办**：文档提交 `180d3fc` 尚未推送

---

## 用户的明确要求（做之前先看）

- 不改 Magpie 源码
- 不在本机编译/构建/验证，一切走云端（GitHub Actions）
- 本地只做静态检查和产物分析
- 尽量复用 Eta 原有代码，不自创
- 不留死代码
- 打开 Magpie 走系统默认浏览器，不搬 WebView
- 只下载 release 包，debug 基本不装
- Magpie 会自更新，版本号不用纠结

详细设计见仓库内 `docs/Eta改动说明.md`。
