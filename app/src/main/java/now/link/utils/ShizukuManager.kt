package now.link.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

enum class ShizukuState {
    UNAVAILABLE,
    AVAILABLE,
    PERMISSION_GRANTED,
}

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    private const val REQUEST_CODE = 10001

    private val _state = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val binderListener: Shizuku.OnBinderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            Log.d(TAG, "Shizuku binder received")
            _isRunning.value = true
            updatePermissionState()
        }

    private val binderDeadListener: Shizuku.OnBinderDeadListener =
        Shizuku.OnBinderDeadListener {
            Log.d(TAG, "Shizuku binder dead")
            _isRunning.value = false
            _state.value = ShizukuState.AVAILABLE
        }

    fun initialize() {
        try {
            // Check Shizuku version
            val version = Shizuku.getVersion()
            if (version < 0) {
                _state.value = ShizukuState.UNAVAILABLE
                return
            }
            _state.value = ShizukuState.AVAILABLE

            Shizuku.addBinderReceivedListener(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)

            _isRunning.value = Shizuku.ping()
            if (_isRunning.value) {
                updatePermissionState()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku not available: ${e.message}")
            _state.value = ShizukuState.UNAVAILABLE
        }
    }

    private fun updatePermissionState() {
        _state.value = when (Shizuku.getStatusCode()) {
            Shizuku.STATUS_GRANTED -> ShizukuState.PERMISSION_GRANTED
            else -> ShizukuState.AVAILABLE
        }
    }

    fun requestPermission() {
        if (_state.value != ShizukuState.AVAILABLE) return
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    fun handlePermissionResult(requestCode: Int, grantResult: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        _state.value = if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ShizukuState.PERMISSION_GRANTED
        } else {
            ShizukuState.AVAILABLE
        }
        Log.d(TAG, "Shizuku permission result: ${_state.value}")
        return true
    }

    suspend fun runCommand(command: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (_state.value != ShizukuState.PERMISSION_GRANTED) {
                return@withContext Pair(false, "Shizuku permission not granted")
            }
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = reader.readText()
            val error = errorReader.readText()
            val exitCode = process.waitFor()
            val result = if (output.isNotEmpty()) output else error
            Pair(exitCode == 0, result)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku command failed", e)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    suspend fun grantPermission(packageName: String, permission: String): Boolean {
        val (success, _) = runCommand("pm grant $packageName $permission")
        return success
    }

    suspend fun grantAllPermissions(packageName: String): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (perm in AdbUtils.ALL_PERMISSIONS) {
            results[perm.permissionString] = grantPermission(packageName, perm.permissionString)
        }
        return results
    }

    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Exception) {
        }
    }
}
