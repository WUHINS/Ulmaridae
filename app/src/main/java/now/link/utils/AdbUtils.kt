package now.link.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AdbPermission(
    val name: String,
    val description: String,
    val permissionString: String,
)

object AdbUtils {
    private const val TAG = "AdbUtils"

    val ALL_PERMISSIONS = listOf(
        AdbPermission(
            name = "DUMP",
            description = "System diagnostics (app usage, running services)",
            permissionString = "android.permission.DUMP"
        ),
        AdbPermission(
            name = "PACKAGE_USAGE_STATS",
            description = "App usage statistics",
            permissionString = "android.permission.PACKAGE_USAGE_STATS"
        ),
        AdbPermission(
            name = "BATTERY_STATS",
            description = "Battery statistics",
            permissionString = "android.permission.BATTERY_STATS"
        ),
        AdbPermission(
            name = "READ_LOGS",
            description = "Read system logs",
            permissionString = "android.permission.READ_LOGS"
        ),
        AdbPermission(
            name = "WRITE_SECURE_SETTINGS",
            description = "Modify system settings (keep-alive optimization)",
            permissionString = "android.permission.WRITE_SECURE_SETTINGS"
        ),
    )

    suspend fun checkAllStatus(packageName: String): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Boolean>()

        for (perm in ALL_PERMISSIONS) {
            val granted = try {
                val process = ProcessBuilder(
                    "sh", "-c",
                    "dumpsys package $packageName 2>/dev/null | grep -qF \"${perm.permissionString}: granted=true\""
                ).start()
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check ${perm.name}: ${e.message}")
                false
            }
            result[perm.permissionString] = granted
        }

        result
    }

    fun generateAdbCommands(packageName: String): List<String> {
        return ALL_PERMISSIONS.map { perm ->
            "adb shell pm grant $packageName ${perm.permissionString}"
        }
    }

    fun generateAdbCommandText(packageName: String): String {
        return generateAdbCommands(packageName).joinToString("\n")
    }

    suspend fun grantViaRoot(packageName: String, permission: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", "pm grant $packageName $permission").start()
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root grant failed for $permission", e)
            false
        }
    }

    suspend fun grantAllViaRoot(packageName: String): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (perm in ALL_PERMISSIONS) {
            results[perm.permissionString] = grantViaRoot(packageName, perm.permissionString)
        }
        return results
    }

    fun isAllGranted(status: Map<String, Boolean>): Boolean {
        return status.isNotEmpty() && status.all { it.value }
    }
}
