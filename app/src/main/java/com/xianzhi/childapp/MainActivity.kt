package com.xianzhi.childapp

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 主Activity
 * 显示当前状态，提供手动操作入口
 */
class MainActivity : AppCompatActivity() {

    private val REQUEST_NOTIFICATION_PERMISSION = 1001

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceOwner: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var btnStartService: Button
    private lateinit var btnSetDeviceOwner: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        tvDeviceOwner = findViewById(R.id.tv_device_owner)
        tvDeviceId = findViewById(R.id.tv_device_id)
        etServerUrl = findViewById(R.id.et_server_url)
        btnStartService = findViewById(R.id.btn_start_service)
        btnSetDeviceOwner = findViewById(R.id.btn_set_device_owner)

        // 显示已保存的服务器地址
        etServerUrl.setText(ServerConfig.getServerUrl(this))

        // 启动/停止服务
        btnStartService.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATION_PERMISSION
                    )
                    return@setOnClickListener
                }
            }
            startControlService()
        }

        // 设备所有者设置提示
        btnSetDeviceOwner.setOnClickListener {
            showDeviceOwnerInstructions()
        }

        // 保存服务器地址
        val btnSaveServer = findViewById<Button>(R.id.btn_save_server)
        btnSaveServer.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ServerConfig.setServerUrl(this, url)
            Toast.makeText(this, "服务器地址已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val isOwner = AppFreezeManager.isDeviceOwner(this)
        tvDeviceOwner.text = if (isOwner) "设备所有者: 已激活" else "设备所有者: 未激活"
        tvDeviceId.text = "设备ID: ${DeviceIdManager.getDeviceId(this)}"
        btnStartService.isEnabled = isOwner
        // 设备所有者激活时确保防卸载
        if (isOwner) {
            AppFreezeManager.protectSelf(this)
        }
    }

    private fun startControlService() {
        try {
            val serviceIntent = Intent(this, WebSocketService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "管控服务已启动", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "启动管控服务失败", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startControlService()
            } else {
                Toast.makeText(this, "需要通知权限才能启动管控服务", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDeviceOwnerInstructions() {
        val message = """
请通过ADB命令设置设备所有者：

1. 确保设备上没有其他设备管理器
2. 连接电脑，执行以下命令：

adb shell dpm set-device-owner com.xianzhi.childapp/.DeviceAdminReceiver

3. 如果提示已有账户，先移除所有账户后重试
        """.trimIndent()

        android.app.AlertDialog.Builder(this)
            .setTitle("设置设备所有者")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }
}
