package com.xianzhi.childapp

import java.security.MessageDigest

/**
 * 基于时间戳的动态密码生成器
 * 孩子端和家长端共享相同算法和密钥
 * 密码每5分钟变化一次
 */
object TimePassword {
    private const val SECRET = "xianzhi_child_control_2024"
    private const val INTERVAL_MS = 5 * 60 * 1000L // 5分钟有效

    /**
     * 根据当前时间生成6位数字密码
     */
    fun generate(): String {
        return generateForTime(System.currentTimeMillis())
    }

    /**
     * 验证密码（允许当前和上一个时间窗口）
     */
    fun verify(input: String): Boolean {
        val now = System.currentTimeMillis()
        return input == generateForTime(now) || input == generateForTime(now - INTERVAL_MS)
    }

    private fun generateForTime(timestamp: Long): String {
        val timeSlot = timestamp / INTERVAL_MS
        val raw = "$SECRET:$timeSlot"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        // 取前4字节转为数字，取模1000000得到6位数
        val num = ((digest[0].toLong() and 0xFF) shl 24) or
                ((digest[1].toLong() and 0xFF) shl 16) or
                ((digest[2].toLong() and 0xFF) shl 8) or
                (digest[3].toLong() and 0xFF)
        return String.format("%06d", Math.abs(num) % 1000000)
    }
}
