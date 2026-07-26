package com.xianzhi.childapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * 应用冻结/解冻管理器
 * 使用设备所有者权限冻结和解冻应用
 */
object AppFreezeManager {

    private const val TAG = "AppFreezeManager"

    /**
     * 冻结指定应用
     * 需要设备所有者权限
     */
    fun freezeApp(context: Context, packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            dpm.setPackagesSuspended(componentName, packageName, true)
            Log.d(TAG, "已冻结应用: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "冻结应用失败: $packageName", e)
            false
        }
    }

    /**
     * 解冻指定应用
     */
    fun unfreezeApp(context: Context, packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            dpm.setPackagesSuspended(componentName, packageName, false)
            Log.d(TAG, "已解冻应用: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "解冻应用失败: $packageName", e)
            false
        }
    }

    /**
     * 检查应用是否被冻结
     */
    fun isAppFrozen(context: Context, packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            dpm.isPackageSuspended(componentName, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "检查应用状态失败: $packageName", e)
            false
        }
    }

    /**
     * 批量冻结应用
     */
    fun freezeApps(context: Context, packageNames: List<String>): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (packageName in packageNames) {
            results[packageName] = freezeApp(context, packageName)
        }
        return results
    }

    /**
     * 批量解冻应用
     */
    fun unfreezeApps(context: Context, packageNames: List<String>): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        for (packageName in packageNames) {
            results[packageName] = unfreezeApp(context, packageName)
        }
        return results
    }

    /**
     * 设置加密DNS（需要设备所有者权限，Android 9+）
     */
    fun setPrivateDns(context: Context, dnsHostname: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setGlobalSetting(
                    componentName,
                    "private_dns_mode",
                    "hostname"
                )
                dpm.setGlobalSetting(
                    componentName,
                    "private_dns_specifier",
                    dnsHostname
                )
                Log.d(TAG, "已设置加密DNS: $dnsHostname")
                true
            } else {
                Log.w(TAG, "Android版本不支持加密DNS设置")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置加密DNS失败", e)
            false
        }
    }

    /**
     * 检查是否具有设备所有者权限
     */
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)
        return dpm.isDeviceOwnerApp(componentName.packageName)
    }

    /**
     * 设置应用防卸载
     * 需要设备所有者权限，阻止用户卸载指定应用
     */
    fun setUninstallBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            dpm.setUninstallBlocked(componentName, packageName, blocked)
            Log.d(TAG, "防卸载设置: $packageName -> $blocked")
            true
        } catch (e: Exception) {
            Log.e(TAG, "防卸载设置失败: $packageName", e)
            false
        }
    }

    /**
     * 保护自身应用防卸载
     * 在服务启动等关键时机调用
     */
    fun protectSelf(context: Context) {
        setUninstallBlocked(context, context.packageName, true)
    }
}
