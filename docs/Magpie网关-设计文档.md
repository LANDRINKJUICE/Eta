# Magpie 网关 — 设计文档（做了什么、怎么实现）

本文件记录本 fork 相对上游 `Mangi-11/Eta` 的全部改动。上游同步时请保留本文件，
新增改动继续追加在文末，不要覆盖历史记录。

- 分支：`feat/magpie-gateway`
- 仓库：`LANDRINKJUICE/Eta`
- 基线提交：`26e9835`（上游 4.0.0）

---

## 1. 目标

在 Eta 内提供 Magpie 模型网关，复用 Eta 既有的 Linux 环境与 Provider 体系，
不改动 Magpie 源码，也不在本地编译任何东西。

用户可见行为：

1. 在「Linux 工具环境 → 可选工具」里手动安装 Magpie（约 96 MB，用户点才下载）
2. 安装后，会话页快捷菜单在「打开终端」下方出现「打开 Magpie」
3. 首次点击弹出运行确认；确认后不再询问
4. 点击打开：启动网关，然后交给**系统默认浏览器**打开面板
5. 运行中时菜单额外出现「关闭 Magpie」，点击需二次确认
6. 「模型提供商」列表内置一项 `Magpie`，baseUrl 指向本机网关

明确不做：不改 Magpie 源码、不在 APK 内打包二进制、不做 WebView 面板、
不做独立容器、不做开机自启、不代管 Magpie 的模型服务商配置。

---

## 2. 关键事实（来自 Magpie 源码核对）

| 项 | 值 | 源码位置 |
|---|---|---|
| 网关 API 端口 | `127.0.0.1:3425` | `internal/gateway/gateway.go` `DefaultAddr` |
| Web 面板端口 | `127.0.0.1:3430` | `internal/cli/web_cli.go` `webAddr` |
| 启动命令 | `magpie serve --web=host:port` | `internal/cli/web_cli.go` `serveCmd` |
| `serve` 不带 `--web` | 只起网关，**没有面板** | `web_cli.go:246` `if web == "" { return serve() }` |
| 面板访问密钥 | `?k=<key>` 或 `MAGPIE_WEB_KEY` 环境变量 | `internal/gui/web.go` `webKey` / `webGuard` |
| 密钥规则 | ≥16 字符，仅 `A-Za-z0-9-._~` | `web.go` `CheckWebKey` / `webKeyMin` |
| 面板链接格式 | `scheme://host:port/?k=<urlencoded>` | `web.go:129` `w.Link` |
| 静态二进制 | `CGO_ENABLED=0`，Alpine/Debian 通用 | `Makefile` |
| 发行渠道 | `yetone/magpie-releases` | 独立 release 仓库 |

要点：`--web` 模式下网关与面板由**同一进程**提供，网关仍在 3425，面板在 3430。

---

## 3. 安装链路

内置版本：`v0.1.4834`，产物 `magpie-cli-linux-arm64`，
大小 `96_207_010`，SHA256 `316e26b3…c011f0cb`。

Magpie 自身有 `update` 子命令会自更新，因此这里的版本号只是初始安装版本。

### 3.1 枚举与制品

`agent/terminal/linux/PinnedLinuxToolInstaller.kt`

- `ManagedLinuxTool` 增加 `MAGPIE`
- `PinnedLinuxToolArtifacts` 增加 `MAGPIE_VERSION`、`MAGPIE_RELEASE_BASE_URL`、
  `magpieArtifact(abis)` 与 `magpieArtifact(architecture, sha256, sizeBytes)`
- 复用 `VerifiedArtifactDownloader` 做大小与 SHA256 校验
- 下载走 `gh-proxy.com` 前缀兜底，与 UV/NODE 一致

### 3.2 激活（两条路径）

Root（chroot）：`magpieActivationCommand` 用 busybox 把下载文件 `cp` 到
`<rootfs>/usr/local/bin/magpie` 并 `chmod 0755`。

免 Root（PRoot）：`activateRootlessMagpie` 复制到 `<rootfs>/opt/eta/magpie`，
再在 `<rootfs>/usr/local/bin/magpie` 写一个 `#!/bin/sh exec /opt/eta/magpie "$@"` 入口。

注意：`archive` 在 App cache、目标在 rootfs，可能跨分区，因此用 `copyTo`
而不是 `renameTo`；`staging` 与 `target` 同目录，`renameTo` 不会跨设备。

### 3.3 Profile 与 UI

`agent/terminal/linux/LinuxPackageProfiles.kt`

- 新增 `MAGPIE` profile，`managedTool = ManagedLinuxTool.MAGPIE`
- `setupScript` 预建 `/root/.config/magpie`
  （chroot 与 PRoot 都以 `HOME=/root` 进入 rootfs，见 `ProotCommandBuilder`）
- `ALL` 加入 `MAGPIE`

`agent/terminal/linux/AlpineEnvironmentPaths.kt`
新增 `MAGPIE_GATEWAY_MARKER`、`MAGPIE_GATEWAY_REVISION`。

`ui/screens/terminal/LinuxEnvironmentScreen.kt`
`InstallTarget` 加 `MAGPIE`，`packageProfileUis` 加一项（数据驱动，UI 自动渲染）。
安装成功后清除首次运行确认标记，使重装后重新询问。

---

## 4. 进程管理

新增 `agent/terminal/linux/MagpieGatewayManager.kt`。

复用 `DetachedTaskSupervisor` 启停（`description = "magpie-gateway"` 作为识别键），
不新造进程管理设施。

启动命令：

```
HOME=/root XDG_CONFIG_HOME=/root/.config MAGPIE_WEB_KEY=<key> exec <magpie> serve --web=127.0.0.1:3430
```

- 就绪判据：连 `127.0.0.1:3430`，500 ms 轮询，最长 20 s
- 就绪失败会回收残留任务
- `stop()` 通过 `supervisor.stop(task.id)`
- 面板密钥：首次生成 32 位 hex（UUID 去连字符）存 DataStore，
  之后固定复用，用 `MAGPIE_WEB_KEY` 注入、用 `?k=` 直接进入面板
- 对外入口 `panelUrl()` 为 suspend，携带密钥

### 已知取舍

- 不处理 Magpie 自更新后的路径变化：自更新若替换二进制但路径不变则无影响
- 不做端口占用探测以外的冲突处理，3430 被占时表现为启动就绪超时

---

## 5. Provider 接入

`data/model/Provider.kt`：`ProviderSourceTypes` 增加 `MAGPIE`。

`data/provider/BuiltinProviders.kt`：新增内置项

```
id = "builtin-magpie", name = "Magpie",
baseUrl = "http://127.0.0.1:3425/v1",
sourceType = MAGPIE, isBuiltIn = true, sortOrder = 12
```

`data/provider/ProviderSourceRegistry.kt`：加入 `knownSourceTypes`，
并加 `MAGPIE_ID → MAGPIE` 映射，否则会被归一化为 `CUSTOM`。

`agent/model/provider/ProviderReasoning.kt`：穷尽式 `when` 补 `MAGPIE` 分支，
沿用 `applyNamedReasoningEffort`（与 `CUSTOM` 一致）。

`ui/components/ProviderBranding.kt`：映射到 `R.drawable.provider_logo_magpie`。

协议走 `OpenAiCompatibleProviderSetting` → `OpenAiChatCompletionsProvider`，
工厂无需改动。

老用户升级后由 `ProviderRepository.ensureBuiltInsMerged()` 自动补入。

---

## 6. UI 落位

### 6.1 会话页快捷菜单

`ui/app/AgentAppShell.kt` 的 `TopBarOverflowMenu`，顺序为：

| 序号 | 项 | 显示条件 |
|---|---|---|
| 1 | 新建对话 | 总是 |
| 2 | 打开终端 | 总是 |
| 3 | 打开 Magpie | 已安装 |
| 4 | 关闭 Magpie | 已安装且运行中 |
| 5 | 打开浏览器 | 总是 |
| 6 | 自动播报 | 有 TTS |

参数经 `AgentAppShell → AgentTopBar → HomeTopBar → TopBarOverflowMenu`
四层传递：`magpieInstalled`、`magpieRunning`、`onOpenMagpie`、`onCloseMagpie`。

### 6.2 状态与动作

`ui/app/AgentAppRoot.kt`

- 状态：`magpieInstalled`、`magpieRunning`、`magpiePendingAction`、`magpieFirstRunConfirmed`
- 首帧在 `LaunchedEffect(magpieManager)` 里异步探测一次，组合期不做文件系统访问
- 动作经 `MagpiePendingAction` 流转：
  - `ConfirmFirstRun` → 确认后写 DataStore 并转 `Open`
  - `ConfirmStop` → 确认后直接 `stop()`
  - `Open` → `start()` 成功后 `ACTION_VIEW` 打开 `panelUrl()`
- 打开使用隐式 Intent，不指定包名，跟随系统默认浏览器；
  无可用浏览器时捕获 `ActivityNotFoundException` 并提示

### 6.3 确认框

复用 `GlassDialog` + `DialogActions`。首次运行与关闭各一条文案，
关闭按钮为 `destructive`。

---

## 7. 图标

两种图标用途不同，不要混用：

| 用途 | 资源 | 形式 |
|---|---|---|
| 菜单项 | `res/drawable/ic_magpie.xml` | 单色矢量，`GlassPopupItem` 要求 `ImageVector`，跟随主题着色 |
| Provider 列表 | `res/drawable-xxxhdpi/provider_logo_magpie.webp` | 128×128 彩色位图，与其他内置 logo 同规格 |

矢量图标由上游 `icon-256.png` 经 potrace 描摹。
路径坐标在 614–1557，实际包围盒换算到 256 viewport 后仅占 61–156，
因此 viewport 设为 `108.46`、平移 `(-54.32, 160.63)`，使鸟形占 87%×91% 并居中。
直接沿用 potrace 的 `scale(0.1)` 会导致图标过小。

---

## 8. 构建

新增 `.github/workflows/magpie-build.yml`，与上游 `android-release.yml` 独立，
避免同步上游时冲突。

- 触发：`push` 到 `feat/**`、`workflow_dispatch`
- 一次构建产出 Debug 与 Release 两个 APK
- 复用 `./.github/actions/setup-build` 复合 action
- 签名沿用四个仓库 Secret：
  `ETA_RELEASE_KEYSTORE_BASE64`、`ETA_RELEASE_STORE_PASSWORD`、
  `ETA_RELEASE_KEY_ALIAS`、`ETA_RELEASE_KEY_PASSWORD`
- 证书 SHA-256：`6cb3cbfc…192d6ea`

签名材料备份位置：`/storage/emulated/0/Download/ETA共享文件夹/eta-signing-backup.zip`
（含 keystore、口令、说明）。**丢失将无法覆盖升级已安装的 Eta。**

---

## 9. 同步上游

```sh
git remote add upstream https://github.com/Mangi-11/Eta.git
git fetch upstream
git rebase upstream/main feat/magpie-gateway
```

预期冲突仅出现在实际修改的文件里；`magpie-build.yml`、`ic_magpie.xml`、
`provider_logo_magpie.webp`、本文件为新增，不会冲突。

---

## 10. 改动文件清单

新增：

```
.github/workflows/magpie-build.yml
app/src/main/kotlin/io/github/mangi/eta/agent/terminal/linux/MagpieGatewayManager.kt
app/src/main/res/drawable/ic_magpie.xml
app/src/main/res/drawable-xxxhdpi/provider_logo_magpie.webp
docs/Magpie网关-设计文档.md
```

修改：

```
agent/model/provider/ProviderReasoning.kt          +1   when 分支
agent/terminal/linux/AlpineEnvironmentPaths.kt     +2   标记与修订号
agent/terminal/linux/LinuxPackageProfiles.kt      +23   MAGPIE profile（-1 为 ALL 列表重写）
agent/terminal/linux/PinnedLinuxToolInstaller.kt  +87   枚举、制品、激活
data/datastore/SettingsDataStore.kt               +30   面板密钥、首次确认
data/model/Provider.kt                             +1   MAGPIE 源类型
data/provider/BuiltinProviders.kt                 +10   内置 Provider
data/provider/ProviderSourceRegistry.kt            +2   注册与映射
ui/app/AgentAppRoot.kt                            +95   状态、动作、确认框
ui/app/AgentAppShell.kt                           +52   菜单项与参数链
ui/components/ProviderBranding.kt                  +1   品牌图标
ui/screens/terminal/LinuxEnvironmentScreen.kt     +15   工具项与标记清理
res/values*/strings.xml                         +12×3   三语文案
```

新增文件行数：`MagpieGatewayManager.kt` 151、`magpie-build.yml` 98、
`ic_magpie.xml` 17、`provider_logo_magpie.webp` 1372 字节。

---

## 11. 变更记录

### 2026-11-26 初版

- 安装、启停、菜单入口、内置 Provider、图标、CI 全链路
- 修复：`vectorResource` 导入路径（应在 `androidx.compose.ui.res`）
- 修复：`AgentAppRoot` 未声明 `appContext`
- 修复：面板端口由 3425 更正为 3430，启动加 `--web`
- 修复：面板需密钥，改为固定 `MAGPIE_WEB_KEY` + `?k=` 进入
- 修复：菜单项位置移到「打开终端」之后
- 修复：Provider 图标改用原生彩色，矢量仅用于菜单
- 修复：矢量图标 viewport 按包围盒重算，修正过小
- 清理：删除未使用参数与无效 `renameTo` 兜底
