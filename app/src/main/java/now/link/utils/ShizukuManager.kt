package now.link.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

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

    private val binderListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        updateState()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        _state.value = ShizukuState.AVAILABLE
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) {
            _state.value = if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ShizukuState.PERMISSION_GRANTED
            } else {
                ShizukuState.AVAILABLE
            }
            Log.d(TAG, "Shizuku permission result: ${_state.value}")
        }
    }

    fun initialize() {
        try {
            val version = Shizuku.getVersion()
            if (version < 0) {
                _state.value = ShizukuState.UNAVAILABLE
                return
            }
            _state.value = ShizukuState.AVAILABLE

            Shizuku.addBinderReceivedListener(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)

            updateState()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku not available: ${e.message}")
            _state.value = ShizukuState.UNAVAILABLE
        }
    }

    private fun updateState() {
        _state.value = if (Shizuku.getVersion() > 0 && Shizuku.getBinder() != null) {
            ShizukuState.PERMISSION_GRANTED
        } else {
            ShizukuState.AVAILABLE
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

    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {
        }
    }
}
