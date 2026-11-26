# 给 AI / 新会话的入口说明

这是 **Eta 的 fork**（上游 `Mangi-11/Eta`），分支 `feat/magpie-gateway`。

**动手之前，先读这两份：**

1. [`Eta交接记录.md`](Eta交接记录.md) — 当前进度、已踩的坑、环境备忘、下一步
2. [`docs/Eta改动说明.md`](docs/Eta改动说明.md) — 相对上游的全部改动与实现细节

## 硬约束（用户明确要求）

- **不在本机编译、构建、验证**。手机不适合，全部走 GitHub Actions。
  本地只允许静态检查（lint、类型检查、语法校验）和产物分析。
- **不改 Magpie 源码**。它是外部依赖，通过 release 二进制接入。
- **尽量复用 Eta 原有代码**，不自造框架。
- **不留死代码**。改完要清理。
- 文件只写 `/data/local/tmp/eta/`（Linux 侧 `/workspace`）
  和 `/storage/emulated/0/Download/ETA共享文件夹/`。

## 快速定位

| 想做什么 | 看哪里 |
|---|---|
| Magpie 的安装逻辑 | `agent/terminal/linux/PinnedLinuxToolInstaller.kt` |
| 网关启停 | `agent/terminal/linux/MagpieGatewayManager.kt` |
| 菜单入口 | `ui/app/AgentAppShell.kt` 的 `TopBarOverflowMenu` |
| 内置 Provider | `data/provider/BuiltinProviders.kt` |
| CI | `.github/workflows/magpie-build.yml` |

## 改动后必须做

1. 更新 `Eta交接记录.md` 的进度与坑
2. 在 `docs/Eta改动说明.md` 的「变更记录」追加一条
3. 推分支，等 CI 出包，**读日志确认成功**再交付
