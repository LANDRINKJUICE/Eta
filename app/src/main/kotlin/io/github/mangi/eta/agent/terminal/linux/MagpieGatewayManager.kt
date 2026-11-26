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
 */
internal class MagpieGatewayManager(
    context: Context,
    private val distribution: LinuxDistribution,
) {

    private val appContext = context.applicationContext
    private val supervisor = DetachedTaskSupervisor(
        AndroidAgentLogger,
        DetachedTaskSupervisor.defaultRecordsFile(appContext),
    )

    private fun rootfs(): File = LinuxEnvironmentPaths.rootfsDir(appContext, distribution)

    private fun environment(): TerminalEnvironment = when (distribution) {
        LinuxDistribution.ALPINE -> TerminalEnvironment.ALPINE
        LinuxDistribution.DEBIAN -> TerminalEnvironment.DEBIAN
    }

    /** 网关是否已安装：rootfs 内存在可执行的 magpie 即视为已安装。 */
    fun installed(): Boolean = magpiePath() != null

    private fun magpiePath(): String? {
        val rootfs = rootfs()
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) return null
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return when {
                File(rootfs, "usr/local/bin/magpie").canExecute() -> "/usr/local/bin/magpie"
                File(rootfs, "opt/eta/magpie").canExecute() -> "/opt/eta/magpie"
                else -> null
            }
        }
        return if (File(rootfs, "usr/local/bin/magpie").canExecute()) "/usr/local/bin/magpie" else null
    }

    private fun gatewayTask(): DetachedTask? =
        supervisor.list().firstOrNull { it.task.description == TASK_DESCRIPTION }?.task

    /** 进程仍在运行。 */
    fun running(): Boolean =
        supervisor.list().any { it.running && it.task.description == TASK_DESCRIPTION }

    private suspend fun answering(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(GATEWAY_HOST, PANEL_PORT), PROBE_TIMEOUT_MS) }
            true
        }.getOrDefault(false)
    }

    private suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (answering()) return true
            delay(500)
        }
        return answering()
    }

    /** 面板访问密钥：magpie 用 MAGPIE_WEB_KEY 固定它，浏览器用 ?k= 直接进入。 */
    private suspend fun panelKey(): String {
        val stored = SettingsDataStore.magpiePanelKey()
        if (stored != null) return stored
        val generated = java.util.UUID.randomUUID().toString().replace("-", "")
        SettingsDataStore.setMagpiePanelKey(generated)
        return generated
    }

    /** 交给系统浏览器打开的面板地址，携带已固定的访问密钥。 */
    suspend fun panelUrl(): String = "$PANEL_URL/?k=${panelKey()}"

    /** 启动网关；已在运行时直接返回可用状态。就绪等待失败会回收残留任务。 */
    suspend fun start(): Boolean {
        if (installed().not()) return false
        if (answering()) return true
        if (running()) stop()
        val executable = magpiePath() ?: return false
        val environment = environment()
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs().absolutePath)
        // MAGPIE_WEB_KEY 固定面板密钥，省去浏览器端手工粘贴。
        val webKey = panelKey()
        // PRoot 与 chroot 都以 HOME=/root 进入 rootfs，见 ProotCommandBuilder 的 env 前缀。
        val result = supervisor.start(
            command = "HOME=/root XDG_CONFIG_HOME=/root/.config MAGPIE_WEB_KEY=$webKey " +
                "exec $executable serve --web=$PANEL_ADDR",
            cwd = "/root",
            identity = identity,
            environment = environment,
            commandTask = false,
            description = TASK_DESCRIPTION,
        )
        val submitted = result is DaemonStartResult.Started
        AndroidAgentLogger.info(
            "Magpie gateway action=start outcome=${if (submitted) "submitted" else "failed"} identity=$identity",
        )
        if (!submitted) return false
        val ready = awaitReady(START_TIMEOUT_MS)
        if (!ready) stop()
        return ready
    }

    /** 停止网关；没有运行中的任务也视为成功。 */
    fun stop(): Boolean {
        val task = gatewayTask() ?: return true
        val stopped = supervisor.stop(task.id)
        AndroidAgentLogger.info(
            "Magpie gateway action=stop outcome=${if (stopped) "succeeded" else "failed"} id=${task.id}",
        )
        return stopped
    }

    companion object {
        private const val GATEWAY_HOST = "127.0.0.1"

        /**
         * Web 面板端口。`serve --web` 用同一进程在 3430 提供面板，网关 API 仍在默认的 3425，
         * 因此就绪探测看 3430，内置 Provider 的 baseUrl 看 3425。
         */
        private const val PANEL_PORT = 3430
        private const val PANEL_ADDR = "$GATEWAY_HOST:$PANEL_PORT"
        private const val TASK_DESCRIPTION = "magpie-gateway"
        private const val PROBE_TIMEOUT_MS = 800
        private const val START_TIMEOUT_MS = 20_000L

        /** 面板根地址，不含访问密钥；对外入口是 [panelUrl]。 */
        private const val PANEL_URL = "http://$GATEWAY_HOST:$PANEL_PORT"
    }
}
