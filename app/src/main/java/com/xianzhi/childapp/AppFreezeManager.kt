package com.xianzhi.childapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.UserManager
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
            dpm.setPackagesSuspended(componentName, arrayOf(packageName), true)
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
            dpm.setPackagesSuspended(componentName, arrayOf(packageName), false)
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
                // 第一步：通过DevicePolicyManager设置
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
                Log.d(TAG, "已通过DPM设置加密DNS: $dnsHostname")

                // 第二步：通过settings命令强制写入并立即生效
                try {
                    Runtime.getRuntime().exec(arrayOf(
                        "settings", "put", "global", "private_dns_mode", "hostname"
                    )).waitFor()
                    Runtime.getRuntime().exec(arrayOf(
                        "settings", "put", "global", "private_dns_specifier", dnsHostname
                    )).waitFor()
                    Log.d(TAG, "已通过settings命令写入DNS设置")
                } catch (e: Exception) {
                    Log.w(TAG, "settings命令写入失败（不影响DPM设置）", e)
                }

                // 第三步：刷新网络让DNS设置立即生效
                try {
                    // 先断开再重连WiFi，强制系统重新读取DNS配置
                    Runtime.getRuntime().exec(arrayOf(
                        "cmd", "connectivity", "airplane-mode", "enable"
                    )).waitFor()
                    Thread.sleep(1500)
                    Runtime.getRuntime().exec(arrayOf(
                        "cmd", "connectivity", "airplane-mode", "disable"
                    )).waitFor()
                    Log.d(TAG, "已切换飞行模式刷新网络")
                } catch (e: Exception) {
                    Log.w(TAG, "刷新网络失败", e)
                }

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

    /**
     * 远程卸载应用（需要设备所有者权限）
     * 使用反射调用隐藏API uninstallPackage
     */
    fun uninstallApp(context: Context, packageName: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            // 尝试反射调用 DevicePolicyManager.uninstallPackage（隐藏API）
            val method = DevicePolicyManager::class.java.getMethod(
                "uninstallPackage",
                ComponentName::class.java,
                String::class.java
            )
            method.invoke(dpm, componentName, packageName)
            Log.d(TAG, "已卸载应用: $packageName")
            true
        } catch (e: NoSuchMethodException) {
            // 反射失败，使用Intent方式卸载
            try {
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.d(TAG, "通过Intent卸载应用: $packageName")
                true
            } catch (ex: Exception) {
                Log.e(TAG, "Intent卸载应用失败: $packageName", ex)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "卸载应用失败: $packageName", e)
            false
        }
    }

    /**
     * 禁止安装应用（包括应用市场和包管理器）
     * 需要设备所有者权限
     */
    fun setInstallBlocked(context: Context, blocked: Boolean): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            if (blocked) {
                dpm.addUserRestriction(componentName, UserManager.DISALLOW_INSTALL_APPS)
                dpm.addUserRestriction(componentName, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            } else {
                // removeUserRestriction 是隐藏API，使用反射调用
                val removeMethod = DevicePolicyManager::class.java.getMethod(
                    "removeUserRestriction",
                    ComponentName::class.java,
                    String::class.java
                )
                removeMethod.invoke(dpm, componentName, UserManager.DISALLOW_INSTALL_APPS)
                removeMethod.invoke(dpm, componentName, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            }
            Log.d(TAG, "安装限制设置: $blocked")
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装限制设置失败", e)
            false
        }
    }

    /**
     * 检查安装是否被禁止
     */
    fun isInstallBlocked(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceAdminReceiver::class.java)

        return try {
            dpm.getUserRestrictions(componentName)
                .getBoolean(UserManager.DISALLOW_INSTALL_APPS, false)
        } catch (e: Exception) {
            false
        }
    }
}
