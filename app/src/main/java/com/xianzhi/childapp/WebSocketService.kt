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
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebSocket后台服务
 * 连接孩子-服务端，接收冻结/解冻指令
 */
class WebSocketService : Service() {

    private val TAG = "WebSocketService"
    private val CHANNEL_ID = "websocket_service"
    private val NOTIFICATION_ID = 1

    private var webSocketClient: WebSocketClient? = null
    private var scheduler: ScheduledExecutorService? = null
    private var isConnecting = false

    private lateinit var deviceId: String
    private lateinit var serverUrl: String

    private val gson = Gson()

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdManager.getDeviceId(this)
        serverUrl = ServerConfig.getServerUrl(this)
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败，通知权限可能未授予", e)
            stopSelf()
            return
        }
        // 确保自身防卸载
        AppFreezeManager.protectSelf(this)
        connectWebSocket()
        startReconnectScheduler()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.close()
        scheduler?.shutdown()
    }

    // ========== WebSocket连接 ==========

    private fun connectWebSocket() {
        if (isConnecting) return
        isConnecting = true

        try {
            val uri = URI("$serverUrl?deviceId=$deviceId")
            webSocketClient = object : WebSocketClient(uri) {
                override fun onOpen(handshake: ServerHandshake?) {
                    Log.d(TAG, "WebSocket连接已建立")
                    isConnecting = false
                    // 连接成功后上报已安装应用列表
                    reportInstalledApps()
                }

                override fun onMessage(message: String?) {
                    Log.d(TAG, "收到消息: $message")
                    handleMessage(message)
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket连接关闭: $reason")
                    isConnecting = false
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket错误", ex)
                    isConnecting = false
                }
            }
            webSocketClient?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket连接失败", e)
            isConnecting = false
        }
    }

    // ========== 消息处理 ==========

    private fun handleMessage(message: String?) {
        if (message.isNullOrEmpty()) return

        try {
            val json = JsonParser.parseString(message).asJsonObject
            val type = json.get("type")?.asString ?: return

            when (type) {
                "frozen_apps" -> {
                    val appsArray = json.getAsJsonArray("apps")
                    val frozenApps = appsArray.map { it.asString }
                    handleFrozenApps(frozenApps)
                }
                "dns_setting" -> {
                    val hostname = json.get("hostname")?.asString
                    val enabled = json.get("enabled")?.asBoolean ?: true
                    handleDnsSetting(hostname, enabled)
                }
                "uninstall_app" -> {
                    val packageName = json.get("packageName")?.asString
                    if (!packageName.isNullOrEmpty()) {
                        handleUninstallApp(packageName)
                    }
                }
                "install_restriction" -> {
                    val blocked = json.get("blocked")?.asBoolean ?: false
                    handleInstallRestriction(blocked)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败", e)
        }
    }

    /**
     * 处理冻结应用列表
     * 1. 获取当前已冻结的应用
     * 2. 对比差异，冻结新应用，解冻不在列表中的应用
     */
    private fun handleFrozenApps(frozenApps: List<String>) {
        val pm = packageManager
        val installedApps = pm.getInstalledApplications(0)
            .map { it.packageName }
            .toSet()

        // 冻结列表中的应用
        val appsToFreeze = frozenApps.filter { it in installedApps }
        for (app in appsToFreeze) {
            AppFreezeManager.freezeApp(this, app)
        }

        // 解冻不在列表中的已冻结应用
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
     * 由家长端远程控制加密DNS的开启/关闭
     */
    private fun handleDnsSetting(hostname: String?, enabled: Boolean) {
        if (enabled && !hostname.isNullOrEmpty()) {
            val success = AppFreezeManager.setPrivateDns(this, hostname)
            Log.d(TAG, "设置加密DNS: $hostname, 结果: $success")
        } else {
            // 关闭加密DNS
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

    /**
     * 处理远程卸载应用
     */
    private fun handleUninstallApp(packageName: String) {
        val success = AppFreezeManager.uninstallApp(this, packageName)
        Log.d(TAG, "远程卸载应用: $packageName, 结果: $success")
    }

    /**
     * 处理安装限制推送
     */
    private fun handleInstallRestriction(blocked: Boolean) {
        val success = AppFreezeManager.setInstallBlocked(this, blocked)
        Log.d(TAG, "安装限制设置: $blocked, 结果: $success")
    }

    // ========== 自动重连 ==========

    private fun startReconnectScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleAtFixedRate({
            if (webSocketClient?.isOpen != true && !isConnecting) {
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

    /**
     * 读取已安装的非系统应用列表，上报给服务端
     */
    private fun reportInstalledApps() {
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { appInfo ->
                    // 过滤掉系统应用和自身
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                            && appInfo.packageName != packageName
                }
                .map { appInfo ->
                    mapOf(
                        "package_name" to appInfo.packageName,
                        "app_name" to (appInfo.loadLabel(pm).toString())
                    )
                }

            val jsonBody = gson.toJson(mapOf(
                "deviceId" to deviceId,
                "apps" to apps
            ))

            // 通过HTTP上报到孩子-服务端
            Thread {
                try {
                    val baseUrl = serverUrl.replace("/ws", "").replace("wss://", "https://").replace("ws://", "http://")
                    val url = "$baseUrl/api/report-apps"
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .writeTimeout(10, TimeUnit.SECONDS)
                        .build()
                    val jsonType = "application/json; charset=utf-8".toMediaType()
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody(jsonType))
                        .build()
                    val response = client.newCall(request).execute()
                    Log.d(TAG, "上报应用列表: ${apps.size}个应用, 结果: ${response.isSuccessful}")
                } catch (e: Exception) {
                    Log.e(TAG, "上报应用列表失败", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "读取应用列表失败", e)
        }
    }
}
