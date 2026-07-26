package com.xianzhi.childapp

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * 设备唯一ID管理
 * 优先使用Android ID，若获取失败则生成UUID并持久化
 */
object DeviceIdManager {

    private const val PREFS_NAME = "child_app_prefs"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * 获取设备唯一ID
     * 首次调用时生成并持久化，后续读取缓存
     */
    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. 先读缓存
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        // 2. 尝试使用Android ID
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val deviceId = if (!androidId.isNullOrEmpty() && androidId != "9774d56d682e549c") {
            "device_$androidId"
        } else {
            // 3. Android ID不可靠时生成UUID
            "device_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        }

        // 持久化保存
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }
}
