package com.xianzhi.childapp

import android.content.Context

/**
 * 服务器地址配置
 * 存储孩子-服务端的WebSocket地址
 */
object ServerConfig {

    private const val PREFS_NAME = "child_app_prefs"
    private const val KEY_SERVER_URL = "server_url"
    private const val DEFAULT_SERVER_URL = "wss://child-server.YOUR-SUBDOMAIN.workers.dev/ws"

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }
}
