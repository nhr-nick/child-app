package com.xianzhi.childapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket后台服务
 * 连接孩子-服务端，接收冻结/解冻指令
 * 使用OkHttp的WebSocket实现
 */
class WebSocketService : Service() {

    private val TAG = "WebSocketService"
    private val CHANNEL_ID = "websocket_service"
    private val NOTIFICATION_ID = 1

    private var webSocket: WebSocket? = null
    private var scheduler: ScheduledExecutorService? = null
    private var isConnecting = false

    private lateinit var deviceId: String
    private lateinit var serverUrl: String

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // WebSocket需要长连接，不设读超时
        .pingInterval(30, TimeUnit.SECONDS) // 每30秒发ping保活
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdManager.getDeviceId(this)
        serverUrl = ServerConfig.getServerUrl(this)
        Log.d(TAG, "服务启动, deviceId=$deviceId, serverUrl=$serverUrl")
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败，通知权限可能未授予", e)
            stopSelf()
            return
        }
        AppFreezeManager.protectSelf(this)

        // 服务启动时立即上报应用（不依赖WebSocket连接）
        reportInstalledApps()

        connectWebSocket()
        startScheduler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Service destroyed")
        scheduler?.shutdown()
    }

    // ========== HTTP工具 ==========

    private fun getHttpBaseUrl(): String {
        return serverUrl
            .replace("/ws", "")
            .replace("wss://", "https://")
            .replace("ws://", "http://")
    }

    // ========== WebSocket连接（OkHttp实现） ==========

    private fun connectWebSocket() {
        if (isConnecting) return
        isConnecting = true

        try {
            val wsUrl = "$serverUrl?deviceId=$deviceId"
            Log.d(TAG, "连接WebSocket: $wsUrl")

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket连接已建立, code=${response.code}")
                    isConnecting = false
                    // 连接成功后再上报一次应用
                    reportInstalledApps()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "收到消息: $text")
                    handleMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket正在关闭: code=$code, reason=$reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket已关闭: code=$code, reason=$reason")
                    isConnecting = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket失败: ${t.message}", t)
                    isConnecting = false
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket连接异常", e)
            isConnecting = false
        }
    }

    // ========== 消息处理 ==========

    private fun handleMessage(message: String) {
        try {
            val json = org.json.JSONObject(message)
            val type = json.optString("type", "")

            when (type) {
                "frozen_apps" -> {
                    val appsArray = json.optJSONArray("apps")
                    val frozenApps = mutableListOf<String>()
                    if (appsArray != null) {
                        for (i in 0 until appsArray.length()) {
                            frozenApps.add(appsArray.getString(i))
                        }
                    }
                    handleFrozenApps(frozenApps)
                }
                "dns_setting" -> {
                    val hostname = json.optString("hostname", "")
                    val enabled = json.optBoolean("enabled", false)
                    handleDnsSetting(hostname, enabled)
                }
                "uninstall_app" -> {
                    val packageName = json.optString("packageName", "")
                    if (packageName.isNotEmpty()) {
                        handleUninstallApp(packageName)
                    }
                }
                "install_restriction" -> {
                    val blocked = json.optBoolean("blocked", false)
                    handleInstallRestriction(blocked)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败: $message", e)
        }
    }

    /**
     * 处理冻结应用列表
     */
    private fun handleFrozenApps(frozenApps: List<String>) {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(0)
            .map { it.packageName }
            .toSet()

        val appsToFreeze = frozenApps.filter { it in installedApps }
        for (app in appsToFreeze) {
            AppFreezeManager.freezeApp(this, app)
        }

        for (installedApp in installedApps) {
            if (installedApp !in frozenApps && installedApp != packageName) {
                if (AppFreezeManager.isAppFrozen(this, installedApp)) {
                    AppFreezeManager.unfreezeApp(this, installedApp)
                }
            }
        }

        Log.d(TAG, "冻结应用处理完成，冻结数量: ${appsToFreeze.size}")
    }

    /**
     * 处理DNS设置推送
     */
    private fun handleDnsSetting(hostname: String, enabled: Boolean) {
        Log.d(TAG, "收到DNS设置推送: hostname=$hostname, enabled=$enabled")
        if (enabled && hostname.isNotEmpty()) {
            val success = AppFreezeManager.setPrivateDns(this, hostname)
            Log.d(TAG, "设置加密DNS: $hostname, 结果: $success")
        } else {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, DeviceAdminReceiver::class.java)
            try {
                dpm.setGlobalSetting(componentName, "private_dns_mode", "off")
                Log.d(TAG, "已关闭加密DNS")
            } catch (e: Exception) {
                Log.e(TAG, "关闭加密DNS失败", e)
            }
        }
    }

    private fun handleUninstallApp(packageName: String) {
        val success = AppFreezeManager.uninstallApp(this, packageName)
        Log.d(TAG, "远程卸载应用: $packageName, 结果: $success")
    }

    private fun handleInstallRestriction(blocked: Boolean) {
        val success = AppFreezeManager.setInstallBlocked(this, blocked)
        Log.d(TAG, "安装限制设置: $blocked, 结果: $success")
    }

    // ========== 定时任务 ==========

    private fun startScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor()

        // 每30秒检查一次，如果没在连接中就尝试重连
        scheduler?.scheduleAtFixedRate({
            if (!isConnecting) {
                Log.d(TAG, "尝试重连WebSocket...")
                connectWebSocket()
            }
        }, 10, 30, TimeUnit.SECONDS)
    }

    // ========== 前台通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "管控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "儿童管控后台服务运行中"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("儿童管控")
                .setContentText("管控服务运行中")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("儿童管控")
                .setContentText("管控服务运行中")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        }
    }

    // ========== 上报已安装应用 ==========

    private fun reportInstalledApps() {
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { appInfo ->
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                            && appInfo.packageName != packageName
                }

            val jsonBody = org.json.JSONObject().apply {
                put("deviceId", deviceId)
                put("apps", org.json.JSONArray().apply {
                    for (appInfo in apps) {
                        put(org.json.JSONObject().apply {
                            put("package_name", appInfo.packageName)
                            put("app_name", appInfo.loadLabel(pm).toString())
                        })
                    }
                })
            }.toString()

            val baseUrl = getHttpBaseUrl()
            Log.d(TAG, "准备上报${apps.size}个应用到 $baseUrl/api/report-apps")

            Thread {
                try {
                    val url = "$baseUrl/api/report-apps"
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody(jsonType))
                        .build()
                    val response = httpClient.newCall(request).execute()
                    val responseBody = response.body?.string()
                    Log.d(TAG, "上报应用列表: ${apps.size}个应用, HTTP ${response.code}, 响应: $responseBody")
                } catch (e: Exception) {
                    Log.e(TAG, "上报应用列表HTTP请求失败: ${e.message}", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "读取应用列表失败", e)
        }
    }
}
