package io.github.mangi.eta.agent.terminal.linux

import android.content.Context
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 管理 Linux 环境内的 Magpie 网关进程。
 *
 * 二进制由 [PinnedLinuxToolInstaller] 安装，本类只负责启停、就绪探测与面板入口；
 * Magpie 自身的模型服务商配置仍走它自己的 CLI 与配置文件，这里不做代理。
 *
 * `serve --web` 以同一进程提供两件事：网关 API 在 3425（供内置 Provider 使用），
 * 面板在 3430（供系统浏览器打开）。就绪探测看 3430。
 */
internal class MagpieGatewayManager(
    context: Context,
    private val distribution: LinuxDistribution,
) {

    private val appContext = context.applicationContext

    private fun rootfsPath(): String =
        LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath

    // 与 AgentLocalTools 一致：必须给出 rootfs，否则 supervisor 取不到路径，
    // 直接返回「Linux rootfs 未配置」，进程不会被拉起。
    // 用 provider 而非固定值：发行版或 backend 变化后 rootfs 会换，supervisor 需跟着取新值。
    private val supervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
        linuxRootfsPathProvider = { rootfsPath() },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )

    private fun environment(): TerminalEnvironment = when (distribution) {
        LinuxDistribution.ALPINE -> TerminalEnvironment.ALPINE
        LinuxDistribution.DEBIAN -> TerminalEnvironment.DEBIAN
    }

    /** rootfs 内可执行的 magpie 路径；chroot 与 PRoot 的落点不同。 */
    private fun magpiePath(): String? {
        val rootfs = File(rootfsPath())
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) return null
        val local = File(rootfs, "usr/local/bin/magpie")
        if (local.canExecute()) return "/usr/local/bin/magpie"
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            val opt = File(rootfs, "opt/eta/magpie")
            if (opt.canExecute()) return "/opt/eta/magpie"
        }
        return null
    }

    /** 网关是否已安装。 */
    suspend fun installed(): Boolean = withContext(Dispatchers.IO) { magpiePath() != null }

    /** 本类启动的守护任务中，优先取仍在运行的那条。 */
    private fun gatewayTask(): DetachedTask? {
        val tasks = supervisor.list().filter { it.task.description == TASK_DESCRIPTION }
        return (tasks.firstOrNull { it.running } ?: tasks.lastOrNull())?.task
    }

    /** supervisor 记录里仍有存活的 magpie 任务。 */
    suspend fun running(): Boolean = withContext(Dispatchers.IO) {
        supervisor.list().any { it.running && it.task.description == TASK_DESCRIPTION }
    }

    /** 面板端口是否已能建立连接。 */
    suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(GATEWAY_HOST, PANEL_PORT), PROBE_TIMEOUT_MS) }
            true
        }.getOrDefault(false)
    }

    private suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (reachable()) return true
            delay(500)
        }
        return reachable()
    }

    /** 面板访问密钥：magpie 用 MAGPIE_WEB_KEY 固定它，浏览器用 ?k= 直接进入。 */
    private suspend fun panelKey(): String {
        val stored = SettingsDataStore.magpiePanelKey()
        if (stored != null) return stored
        // MAGPIE_WEB_KEY 要求 ≥16 字符且仅含 [A-Za-z0-9-._~]，32 位 hex 与之兼容。
        val generated = java.util.UUID.randomUUID().toString().replace("-", "")
        SettingsDataStore.setMagpiePanelKey(generated)
        return generated
    }

    /** 交给系统浏览器打开的面板地址，携带已固定的访问密钥。 */
    suspend fun panelUrl(): String = "$PANEL_URL/?k=${panelKey()}"

    /** 启动网关；已可用时直接返回 true。就绪等待失败会回收残留任务。 */
    suspend fun start(): Boolean {
        // supervisor.start 内部用 ProcessBuilder + waitFor，最长阻塞 10 秒，必须离开主线程。
        val executable = withContext(Dispatchers.IO) { magpiePath() } ?: return false
        if (reachable()) return true
        if (running()) stop()
        val environment = environment()
        val identity = TerminalRuntime.defaultIdentity(environment, rootfsPath())
        val webKey = panelKey()
        // chroot 与 PRoot 都以 HOME=/root 进入 rootfs（见 ProotCommandBuilder 的 env 前缀）。
        val started = withContext(Dispatchers.IO) {
            supervisor.start(
                command = "HOME=/root XDG_CONFIG_HOME=/root/.config MAGPIE_WEB_KEY=$webKey " +
                    "exec $executable serve --web=$PANEL_ADDR",
                cwd = "/root",
                identity = identity,
                environment = environment,
                commandTask = false,
                description = TASK_DESCRIPTION,
            ) is DaemonStartResult.Started
        }
        AndroidAgentLogger.info(
            "Magpie action=start outcome=${if (started) "submitted" else "failed"} identity=$identity",
        )
        if (!started) return false
        val ready = awaitReady(START_TIMEOUT_MS)
        if (!ready) stop()
        return ready
    }

    /** 停止网关；没有运行中的任务也视为成功。 */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        val task = gatewayTask() ?: return@withContext true
        val stopped = supervisor.stop(task.id)
        AndroidAgentLogger.info(
            "Magpie action=stop outcome=${if (stopped) "succeeded" else "failed"} id=${task.id}",
        )
        stopped
    }

    /**
     * 卸载网关：先停进程，再删二进制与完成标记，最后清掉首次运行授权。
     * 配置目录保留，重装后 provider 设置不用重配。
     */
    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        stop()
        val rootfs = File(rootfsPath())
        val targets = listOf(
            File(rootfs, "usr/local/bin/magpie"),
            File(rootfs, "opt/eta/magpie"),
            File(rootfs, LinuxPackageProfiles.MAGPIE.markerName),
        )
        // 逐个删除，不短路：某个文件删不掉时其余仍需清理，避免留下半个安装。
        val failures = targets.count { file -> file.exists() && !file.delete() }
        SettingsDataStore.setMagpieFirstRunConfirmed(false)
        AndroidAgentLogger.info(
            "Magpie action=uninstall outcome=${if (failures == 0) "succeeded" else "failed"} failures=$failures",
        )
        failures == 0
    }

    companion object {
        private const val GATEWAY_HOST = "127.0.0.1"

        /** 面板端口，magpie 的默认值。网关 API 仍在 3425。 */
        private const val PANEL_PORT = 3430
        private const val PANEL_ADDR = "$GATEWAY_HOST:$PANEL_PORT"
        private const val TASK_DESCRIPTION = "magpie-gateway"
        private const val PROBE_TIMEOUT_MS = 800
        private const val START_TIMEOUT_MS = 20_000L

        /** 面板根地址，不含访问密钥；对外入口是 [panelUrl]。 */
        private const val PANEL_URL = "http://$GATEWAY_HOST:$PANEL_PORT"
    }
}
