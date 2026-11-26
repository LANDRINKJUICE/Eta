# Eta 交接记录

> 这份文件是给「下一个接手的人」看的，不是给用户看的说明书。
> 目标：5 分钟内知道做了什么、在哪、为什么这么做、坑在哪、下一步做什么。

**最后更新**：2026-11-26

---

## 一句话状态

Magpie 网关已集成进 Eta fork，**真机验证通过，功能可用**。
最新构建：run `45801723768`，提交 `0006aaf`，APK 在
`/storage/emulated/0/Download/ETA共享文件夹/APK/Eta-Magpie-v4.0.0+0006aaf-release.apk`。

同时完成了另一件事：把 AI Gateway 导出的 31 个账号按提供商分类，
输出到 `/storage/emulated/0/Download/ETA共享文件夹/凭据分类/`。

---

## 仓库与分支

| 项 | 值 |
|---|---|
| 工作目录 | `/workspace/02-项目projects/Eta`（= `/data/local/tmp/eta/02-项目projects/Eta`） |
| origin | `git@github.com:Mangi-11/Eta.git`（上游，只读同步） |
| fork | `git@github.com:LANDRINKJUICE/Eta.git`（推送目标） |
| 分支 | `feat/magpie-gateway` |
| 基线 | `26e9835`（上游 4.0.0） |
| 最新提交 | `0006aaf` |

提交脉络：
`e80065e` 修编译错误 → `ea775ad` 修功能缺陷 → `180d3fc`/`eed700f` 文档
→ `d25c608`/`b9cbf35` 菜单收敛 → `0006aaf` 补导入

其他参考仓库（只读，不改）：
- `/workspace/02-项目projects/magpie` — Magpie 源码，稀疏克隆（只有 `internal/` 和 `cmd/`）
- `/workspace/02-项目projects/magpie.partial` — 早期中断的完整克隆残留，可删

---

## 做了什么

把 Magpie（本地 LLM 网关）作为可选组件接进 Eta：

1. **安装**：Linux 工具环境页多一个「Magpie 网关」条目，用户点了才下载（96 MB）
2. **启动/打开**：会话页菜单只有「打开 Magpie」一项，未运行先启动再开面板
3. **停止/卸载**：在 Linux 工具页的 Magpie 行，运行时显示「停止」，已装显示「卸载」（带确认）
4. **Provider**：模型提供商列表内置 `Magpie` 一项，指向本机网关 3425

不改 Magpie 源码，不打包进 APK，不做独立容器。

### 最终交互设计（几轮迭代后的结论）

菜单只放「打开 Magpie」，**不放关闭**——因为关闭项会占位置，且运行时会顶掉打开入口。
启停归到 Linux 工具页，与「安装」在同一处，符合心智。

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

### 修改（相对基线 `26e9835` 的实际行数）

```
PinnedLinuxToolInstaller.kt    +87    ManagedLinuxTool.MAGPIE + artifact + 两条激活路径
LinuxPackageProfiles.kt        +23 -1 MAGPIE profile（ALL 列表那行重写）
AlpineEnvironmentPaths.kt       +2    marker + revision
SettingsDataStore.kt           +30    magpiePanelKey / magpieFirstRunConfirmed
Provider.kt                     +1    ProviderSourceTypes.MAGPIE
BuiltinProviders.kt            +10    内置 Provider（sortOrder 12）
ProviderSourceRegistry.kt       +2    knownSourceTypes + ID 映射（**漏了会归一化成 CUSTOM**）
ProviderReasoning.kt            +1    穷尽式 when 补分支（**漏了编译不过**）
AgentAppRoot.kt               +123 -1 状态、动作、确认框、生命周期监听
AgentAppShell.kt               +39 -1 菜单项 + 四层参数传递 + 展开前刷新
ProviderBranding.kt             +1    图标映射
LinuxEnvironmentScreen.kt     +106    InstallTarget.MAGPIE + 启停卸载按钮 + 确认框
strings.xml ×3                 +14×3  三语文案
```

新增文件行数：`MagpieGatewayManager.kt` 185、`magpie-build.yml` 98、`ic_magpie.xml` 17。

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
| 8 | 启动卡界面 | `supervisor.start()` 内部 `ProcessBuilder + waitFor` 阻塞主线程最长 10s | 包进 `withContext(Dispatchers.IO)` |
| 9 | 换 backend 后失效 | `linuxRootfsPath` 构造时固化，chroot↔PRoot 切换后指向旧路径 | 改用 `linuxRootfsPathProvider` 动态取值 |
| 10 | 装完菜单不出现 | 路由切换不触发 `ON_RESUME` | 监听 `snapshotFlow { backStack }`，回 Home 时刷新 |
| 11 | 按钮永久变灰 | 停止/卸载的协程缺 `try/finally`，异常时 `busy` 不释放 | 两处都加 `finally` |
| 12 | 对话框无毛玻璃 | `GlassDialog` 漏传 `backdrop`（全项目 36 处都传了） | 补 `currentGlassOverlayBackdrop()` |
| 13 | 日志风格不一致 | 写成 `Magpie gateway action=...` | 改为 `Magpie action=...`，对齐 `<名词> action=<动词>` |
| 14 | 首次确认不弹 | 装完清了磁盘标记但内存状态没同步 | 标记读取并入 `refreshMagpieState()` |
| 15 | **编译失败** | `LinuxEnvironmentScreen` 新增 `LaunchedEffect` 但漏导入（该文件此前从未用过它） | 补 `import androidx.compose.runtime.LaunchedEffect` |

### 从坑里总结的检查清单

静态检查能发现「多余」，发现不了「缺失」。第 15 个坑就是这么漏的——括号平衡、
死代码扫描、未使用导入检查全部通过，但缺导入照样编译失败。

提交前至少跑这几项：

```sh
# 1. 括号平衡
# 2. XML / YAML 合法
# 3. 未使用导入（查多余）
# 4. 新增 API 是否有对应导入（查缺失）← 第 15 个坑的教训
# 5. 新增调用是否与项目其余调用参数一致（查惯例）← 第 12 个坑的教训
```

第 4 项可以这样查：

```sh
for f in $(git diff --name-only <baseline> | grep '\.kt$'); do
  for api in LaunchedEffect DisposableEffect remember mutableStateOf stringResource \
             vectorResource ImageVector PaddingValues collectAsStateWithLifecycle; do
    used=$(grep -c "\b$api\b" "$f")
    imported=$(grep -c "import .*\.$api$" "$f")
    [ "$used" -gt 0 ] && [ "$imported" -eq 0 ] && echo "缺导入: $f -> $api"
  done
done
```

第 5 项：改动 UI 组件调用前，先 `grep` 全项目同名调用，对比参数列表。

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

1. **真机验证**：已完成，用户确认可用。
2. **仍未验证的风险点**：
   - 3430 被占用时的表现（当前是就绪超时，无明确提示）
   - 首次下载 96 MB 的断点续传（`VerifiedArtifactDownloader` 是否支持）
   - 切换 chroot↔PRoot 后启停是否真的正常（代码已按 provider 动态取值，未实测）

已排除：Magpie **确实会自更新**，`last-version` 在 18:41 更新，二进制从
96,141,474（v0.1.4827）变为 96,207,010（v0.1.4834），路径未变，`magpiePath()` 不受影响。

3. **可选的后续**：把 raccoon 的 7 个账号导入 Magpie（见下节）。

---

## Magpie 数据目录

```
/data/data/io.github.mangi.eta/files/terminal/debian/rootfs/root/.config/magpie/
（Linux 侧即 /root/.config/magpie）
```

| 文件 | 作用 |
|---|---|
| `providers.json` | 模型服务商配置（含密钥），当前有 trae-cn / trae-global |
| `logins.json` | 各订阅账号登录态 |
| `settings.json` / `gateway.json` | 网关设置与状态 |
| `quotas.json` / `quota-history.json` | 配额 |
| `usage.jsonl` | 用量流水（持续增长，已 87 KB） |
| `plugins/`、`plugins.json` | 已装插件 |
| `plugin-providers.json`、`plugin-auth.json` | 插件提供的服务商与授权 |

**已装插件**：`@magpie-community/opencode-trae-auth`、`@magpie-community/opencode-raccoon-auth`

**卸载不清这个目录**：`uninstall()` 只删二进制和完成标记，配置保留，重装即恢复。

---

## 凭据分类（另一件事）

用户提供了 `ai-gateway-accounts_1.json`（AI Gateway 导出格式，31 个账号），
已按提供商拆分到：

```
/storage/emulated/0/Download/ETA共享文件夹/凭据分类/
├── _索引.json                    汇总（不含凭据）
├── raccoon-商汤小浣熊.json        7 个
├── codebuddy-腾讯CodeBuddy.json   9 个
├── loomy-Loomy.json              7 个
└── trae-Trae.json                8 个
```

四个提供商的 secret 字段结构不同：raccoon 有 `accessToken`/`refreshToken`/`expiresAt`，
codebuddy 多 `domain`/`enterpriseId`，loomy 用 `session`，trae 是 `auth`/`account`。

**raccoon 已实测可用**：`GET https://xiaohuanxiong.com/api/web/points/v1/balance`
带 `Authorization: Bearer <accessToken>` 返回 200，7 个 token 都在有效期。

**怎么导入 Magpie**：raccoon 插件已装，但未声明 `importFrom`，
所以 `magpie accounts import raccoon` 不可用。只能走面板的「手动 Token」入口，
粘贴 `{"accessToken":"...","refreshToken":"..."}`。7 个账号需粘 7 次。

raccoon 是 OpenAI 兼容接口：`/api/web/llm/v2/chat/completions`。

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
- **UI 改动要对照项目既有调用惯例**（参数、日志格式），不凭感觉写

详细设计见仓库内 `docs/Eta改动说明.md`。
