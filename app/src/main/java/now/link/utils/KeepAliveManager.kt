package now.link.utils

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku

enum class KeepAliveLevel {
    STANDARD,
    ADB,
    SHIZUKU,
    ROOT,
}

data class KeepAliveStatus(
    val appliedLevel: KeepAliveLevel = KeepAliveLevel.STANDARD,
    val isAppStandbyWhitelisted: Boolean = false,
    val isBatteryWhitelisted: Boolean = false,
    val isBackgroundRestricted: Boolean = true,
    val watchdogRunning: Boolean = false,
    val oomAdjusted: Boolean = false,
)

object KeepAliveManager {

    private const val TAG = "KeepAliveManager"
    private var watchdogJob: Job? = null
    private var currentStatus = KeepAliveStatus()

    // ── Level 1: ADB (requires WRITE_SECURE_SETTINGS) ──

    suspend fun optimizeAdb(context: Context): KeepAliveStatus = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val cmds = mutableListOf<String>()

        cmds.add("settings put global app_standby_whitelist \"$pkg\"")
        cmds.add("settings put global battery_disable_optimization_whitelist \"$pkg\"")

        val succeeded = mutableListOf<Boolean>()
        for (cmd in cmds) {
            val (ok, _) = execShell(cmd)
            succeeded.add(ok)
        }

        currentStatus = currentStatus.copy(
            appliedLevel = if (succeeded.any { it }) KeepAliveLevel.ADB else currentStatus.appliedLevel,
            isAppStandbyWhitelisted = succeeded.getOrElse(0) { false },
            isBatteryWhitelisted = succeeded.getOrElse(1) { false } || isBatteryOptimizationDisabled(context),
        )

        LogManager.i(TAG, "ADB keep-alive: standby=${currentStatus.isAppStandbyWhitelisted}, battery=${currentStatus.isBatteryWhitelisted}")
        currentStatus
    }

    // ── Level 2: Shizuku (shell UID) ──

    suspend fun optimizeShizuku(context: Context): KeepAliveStatus = withContext(Dispatchers.IO) {
        if (ShizukuManager.state.value != ShizukuState.PERMISSION_GRANTED) {
            LogManager.w(TAG, "Shizuku not granted, skipping shizuku optimizations")
            return@withContext currentStatus
        }

        val pkg = context.packageName
        val cmds = listOf(
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND_STANDBY allow",
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg WAKE_LOCK allow",
            "cmd settings put global app_standby_whitelist \"$pkg\"",
        )

        val succeeded = mutableListOf<Boolean>()
        for (cmd in cmds) {
            val (ok, _) = execShizuku(cmd)
            succeeded.add(ok)
        }

        currentStatus = currentStatus.copy(
            appliedLevel = KeepAliveLevel.SHIZUKU,
            isBackgroundRestricted = false,
            isAppStandbyWhitelisted = true,
        )

        LogManager.i(TAG, "Shizuku keep-alive: background ops=${!currentStatus.isBackgroundRestricted}")
        currentStatus
    }

    // ── Level 3: Root (oom_adj) ──

    suspend fun optimizeRoot(): KeepAliveStatus = withContext(Dispatchers.IO) {
        if (!RootUtils.isRootAvailable()) {
            LogManager.w(TAG, "Root not available, skipping oom adjustment")
            return@withContext currentStatus
        }

        val pid = android.os.Process.myPid()
        val cmds = listOf(
            "echo -17 > /proc/$pid/oom_score_adj",
            "echo -1000 > /proc/$pid/oom_adj",
        )

        val succeeded = mutableListOf<Boolean>()
        for (cmd in cmds) {
            val (ok, _) = RootUtils.executeRootCommand(cmd)
            succeeded.add(ok)
        }

        currentStatus = currentStatus.copy(
            appliedLevel = KeepAliveLevel.ROOT,
            oomAdjusted = succeeded.any { it },
        )

        LogManager.i(TAG, "Root keep-alive: oom_adjusted=${currentStatus.oomAdjusted}")
        currentStatus
    }

    // ── Level 4: Full optimization (auto-detect best available) ──

    suspend fun optimizeAll(context: Context): KeepAliveStatus {
        optimizeAdb(context)
        optimizeShizuku(context)
        optimizeRoot()
        return currentStatus
    }

    // ── Watchdog (periodic service health check via Shizuku) ──

    fun startWatchdog(context: Context, scope: CoroutineScope) {
        if (ShizukuManager.state.value != ShizukuState.PERMISSION_GRANTED) {
            LogManager.d(TAG, "Shizuku not available, watchdog skipped")
            return
        }

        stopWatchdog()
        watchdogJob = scope.launch {
            val pkg = context.packageName
            val serviceClass = "now.link.service.UnifiedAgentService"
            LogManager.i(TAG, "Watchdog started")

            while (isActive) {
                delay(30_000L) // check every 30s

                try {
                    val checkCmd = "pidof $pkg 2>/dev/null || pgrep -f $pkg 2>/dev/null || echo DEAD"
                    val (ok, output) = execShizuku(checkCmd)
                    val alive = ok && !output.contains("DEAD")

                    if (!alive) {
                        LogManager.w(TAG, "Process dead, restarting service via Shizuku")
                        execShizuku(
                            "am start-foreground-service --user 0 $pkg/$serviceClass"
                        )
                    }
                } catch (e: Exception) {
                    LogManager.w(TAG, "Watchdog check failed: ${e.message}")
                }
            }
        }
        currentStatus = currentStatus.copy(watchdogRunning = true)
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
        currentStatus = currentStatus.copy(watchdogRunning = false)
        LogManager.d(TAG, "Watchdog stopped")
    }

    fun getStatus(): KeepAliveStatus = currentStatus

    // ── Helpers ──

    private suspend fun execShell(cmd: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("sh", "-c", cmd).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            Pair(exit == 0, output)
        } catch (e: Exception) {
            Pair(false, e.message ?: "")
        }
    }

    private suspend fun execShizuku(cmd: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exit = process.waitFor()
            val text = if (output.isNotEmpty()) output else error
            Pair(exit == 0, text)
        } catch (e: Exception) {
            Pair(false, e.message ?: "")
        }
    }

    private fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun cleanup() {
        stopWatchdog()
    }
}
