package now.link.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import now.link.MainActivity
import now.link.R
import now.link.utils.Constants
import now.link.utils.LogManager
import now.link.utils.RootUtils
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class ShellProxyService : Service() {

    companion object {
        const val PROXY_PORT = 13452
        const val ACTION_START_PROXY = "action_start_shell_proxy"
        const val ACTION_STOP_PROXY = "action_stop_shell_proxy"
        const val ENV_SHELL_PROXY = "SHELL_PROXY_URL"
        private const val TAG = "ShellProxy"
    }

    private var serverSocket: ServerSocket? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LogManager.d(TAG, "Shell proxy service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_PROXY -> {
                stopProxy()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(
                    Constants.Service.NOTIFICATION_ID + 1,
                    createNotification()
                )
                startProxy()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startProxy() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PROXY_PORT, 50, InetAddress.getByName("127.0.0.1"))
                LogManager.d(TAG, "Shell proxy listening on 127.0.0.1:$PROXY_PORT")

                while (isActive) {
                    try {
                        val client = serverSocket!!.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (!isActive) break
                        LogManager.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Proxy server failed", e)
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 30000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

                val startLine = reader.readLine() ?: return
                if (!startLine.startsWith("POST /exec")) {
                    writeHttpResponse(writer, 404, """{"error":"not found"}""")
                    return
                }

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val n = reader.read(buf, offset, contentLength - offset)
                        if (n < 0) break
                        offset += n
                    }
                    String(buf, 0, offset)
                } else ""

                val command = extractCommand(body)
                if (command.isNullOrEmpty()) {
                    writeHttpResponse(writer, 400, """{"error":"empty command"}""")
                    return
                }

                val result = executeCommand(command)
                val json = buildJsonResult(result)
                writeHttpResponse(writer, 200, json)
            }
        } catch (e: Exception) {
            LogManager.e(TAG, "Client handler error", e)
        }
    }

    private suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val processBuilder = if (RootUtils.isRootAvailable()) {
                ProcessBuilder("su", "-c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }
            processBuilder.redirectErrorStream(false)

            val process = processBuilder.start()
            val stdout = readStream(process.inputStream)
            val stderr = readStream(process.errorStream)
            val exitCode = process.waitFor()

            ShellResult(stdout, stderr, exitCode)
        } catch (e: Exception) {
            LogManager.e(TAG, "Command execution failed", e)
            ShellResult("", e.message ?: "Unknown error", -1)
        }
    }

    private fun readStream(inputStream: InputStream): String {
        return try {
            BufferedReader(InputStreamReader(inputStream)).readText()
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractCommand(body: String): String? {
        return try {
            val commandKey = "\"command\""
            val start = body.indexOf(commandKey)
            if (start < 0) return null
            val valueStart = body.indexOf('"', start + commandKey.length + 1)
            if (valueStart < 0) return null
            val valueEnd = body.indexOf('"', valueStart + 1)
            if (valueEnd < 0) return null
            body.substring(valueStart + 1, valueEnd).replace("\\n", "\n").replace("\\t", "\t")
        } catch (e: Exception) {
            null
        }
    }

    private fun buildJsonResult(result: ShellResult): String {
        val stdout = result.stdout.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val stderr = result.stderr.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"stdout":"$stdout","stderr":"$stderr","exitCode":${result.exitCode}}"""
    }

    private fun writeHttpResponse(writer: BufferedWriter, status: Int, body: String) {
        val statusText = when (status) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        writer.write("HTTP/1.1 $status $statusText\r\n")
        writer.write("Content-Type: application/json\r\n")
        writer.write("Content-Length: ${body.toByteArray().size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.write(body)
        writer.flush()
    }

    private fun stopProxy() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        LogManager.d(TAG, "Shell proxy stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "shell_proxy_channel",
                "Shell Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Local shell execution proxy for agent"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val activityIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "shell_proxy_channel")
            .setContentTitle("Shell Proxy")
            .setContentText("Local shell execution proxy running")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(activityIntent)
            .setOngoing(true)
            .build()
    }

    private data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int)
}
