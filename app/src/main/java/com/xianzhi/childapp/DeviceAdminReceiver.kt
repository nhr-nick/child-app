package com.xianzhi.childapp

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理员接收器
 * 通过ADB命令激活设备所有者：
 * adb shell dpm set-device-owner com.xianzhi.childapp/.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(TAG, "设备管理员已启用")
        // 启用时自动设置防卸载
        AppFreezeManager.setUninstallBlocked(context, context.packageName, true)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d(TAG, "设备管理员已禁用")
    }

    /**
     * 阻止用户取消设备管理员
     * 返回警告文字，用户无法点击确定取消管理员
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "儿童管控应用不可关闭，否则将失去设备管控功能"
    }

    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }
}
