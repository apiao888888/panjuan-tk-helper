package com.tiktokassist.utils

import android.content.Context
import android.provider.Settings
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 判官TK助手 · 授权管理器
 *
 * 授权码算法说明：
 *  - 授权码绑定设备 Android ID，换机失效
 *  - 内嵌有效期（精确到天）
 *  - 使用 HMAC-SHA256 防伪造
 *  - 格式：XXXXX-XXXXX-XXXXX-XXXXX（20位大写字母+数字，去掉易混淆字符）
 */
object LicenseManager {

    // ==================== 常量 ====================

    // Base32 字符表（去掉易混淆的 I/O/0/1）
    private const val BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private const val PREFS_NAME = "pj_license"
    private const val KEY_LICENSE_CODE = "license_code"
    private const val KEY_EXPIRE_DAYS = "expire_epoch_days"
    private const val KEY_DEVICE_ID = "bound_device_id"

    // 密钥（多段拼接+XOR混淆，增加逆向难度）
    private fun buildSecretKey(): ByteArray {
        val p1 = byteArrayOf(0x50, 0x61, 0x6E, 0x47, 0x75, 0x61, 0x6E, 0x54)  // "PanGuanT"
        val p2 = byteArrayOf(0x4B, 0x41, 0x73, 0x73, 0x69, 0x73, 0x74, 0x21)  // "KAssist!"
        val xorKey = byteArrayOf(0x17, 0x3C, 0x5A, 0x2B, 0x09, 0x4E, 0x7F, 0x33,
                                  0x21, 0x6D, 0x08, 0x55, 0x3A, 0x1C, 0x72, 0x44)
        val combined = p1 + p2
        return ByteArray(combined.size) { i -> (combined[i].toInt() xor xorKey[i % xorKey.size]).toByte() }
    }

    // ==================== 设备ID ====================

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return androidId?.uppercase()?.take(16) ?: "UNKNOWN_DEVICE00"
    }

    // ==================== 授权码生成（注册机用）====================

    /**
     * 生成授权码
     * @param deviceId 目标设备的 Android ID
     * @param validDays 有效天数（从今天起）
     */
    fun generateKey(deviceId: String, validDays: Int): String {
        val todayEpoch = (System.currentTimeMillis() / 86400000L).toInt()
        val expiryEpoch = todayEpoch + validDays

        val payload = buildPayload(deviceId.uppercase(), expiryEpoch)
        val encoded = base32Encode(payload)
        // 格式化：XXXXX-XXXXX-XXXXX-XXXXX
        return encoded.chunked(5).joinToString("-")
    }

    /** 生成永久授权码（有效期50年） */
    fun generatePermanentKey(deviceId: String): String = generateKey(deviceId, 365 * 50)

    // ==================== 授权码验证 ====================

    enum class LicenseResult {
        VALID,              // 有效
        EXPIRED,            // 已过期
        INVALID_DEVICE,     // 设备不匹配
        INVALID_FORMAT,     // 格式错误
        INVALID_SIGNATURE,  // 签名错误（伪造）
        NOT_ACTIVATED       // 未激活
    }

    data class LicenseInfo(
        val result: LicenseResult,
        val expiryEpochDays: Int = 0,
        val daysRemaining: Int = 0,
        val isValid: Boolean = result == LicenseResult.VALID
    )

    fun validateKey(context: Context, key: String): LicenseInfo {
        val cleanKey = key.replace("-", "").replace(" ", "").uppercase().trim()
        if (cleanKey.length != 20) return LicenseInfo(LicenseResult.INVALID_FORMAT)

        val payload = base32Decode(cleanKey) ?: return LicenseInfo(LicenseResult.INVALID_FORMAT)
        if (payload.size < 12) return LicenseInfo(LicenseResult.INVALID_FORMAT)

        // 从 payload 解出有效期
        val expiryEpoch = ((payload[8].toInt() and 0xFF) shl 24) or
                          ((payload[9].toInt() and 0xFF) shl 16) or
                          ((payload[10].toInt() and 0xFF) shl 8) or
                          (payload[11].toInt() and 0xFF)

        val todayEpoch = (System.currentTimeMillis() / 86400000L).toInt()
        val daysRemaining = expiryEpoch - todayEpoch

        if (daysRemaining < 0) return LicenseInfo(LicenseResult.EXPIRED, expiryEpoch, daysRemaining)

        // 验证设备绑定
        val deviceId = getDeviceId(context)
        val expectedPayload = buildPayload(deviceId, expiryEpoch)

        // 比对前8字节（HMAC截断）
        for (i in 0..7) {
            if (payload[i] != expectedPayload[i]) {
                return LicenseInfo(LicenseResult.INVALID_DEVICE, expiryEpoch, daysRemaining)
            }
        }

        return LicenseInfo(LicenseResult.VALID, expiryEpoch, daysRemaining)
    }

    // ==================== 本地授权状态持久化 ====================

    fun saveActivation(context: Context, key: String, expiryEpoch: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LICENSE_CODE, key)
            .putInt(KEY_EXPIRE_DAYS, expiryEpoch)
            .putString(KEY_DEVICE_ID, getDeviceId(context))
            .apply()
    }

    fun clearActivation(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun getSavedKey(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LICENSE_CODE, null)

    /** 快速检查本地缓存的授权是否有效（每次启动调用） */
    fun checkActivation(context: Context): LicenseInfo {
        val savedKey = getSavedKey(context) ?: return LicenseInfo(LicenseResult.NOT_ACTIVATED)
        return validateKey(context, savedKey)
    }

    // ==================== 有效期转换工具 ====================

    fun epochDaysToDateString(epochDays: Int): String {
        val ms = epochDays.toLong() * 86400000L
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return "${cal.get(java.util.Calendar.YEAR)}-" +
               "${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}-" +
               "${cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
    }

    fun validDaysDescription(days: Int) = when {
        days >= 365 * 10 -> "永久授权"
        days >= 365 -> "${days / 365} 年"
        days >= 30 -> "${days / 30} 个月"
        else -> "$days 天"
    }

    // ==================== 内部工具函数 ====================

    private fun buildPayload(deviceId: String, expiryEpoch: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(buildSecretKey(), "HmacSHA256"))
        val data = "$deviceId|$expiryEpoch".toByteArray(Charsets.UTF_8)
        val hmac = mac.doFinal(data)

        val payload = ByteArray(12)
        System.arraycopy(hmac, 0, payload, 0, 8)  // 前8字节：HMAC截断
        payload[8]  = (expiryEpoch shr 24).toByte() // 后4字节：有效期
        payload[9]  = (expiryEpoch shr 16).toByte()
        payload[10] = (expiryEpoch shr 8).toByte()
        payload[11] = expiryEpoch.toByte()
        return payload
    }

    private fun base32Encode(data: ByteArray): String {
        val result = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                result.append(BASE32[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) result.append(BASE32[(buffer shl (5 - bitsLeft)) and 0x1F])
        return result.toString().take(20)  // 12 bytes → 需要 ceil(12*8/5)=20 字符
    }

    private fun base32Decode(s: String): ByteArray? {
        return try {
            val result = mutableListOf<Byte>()
            var buffer = 0
            var bitsLeft = 0
            for (c in s.uppercase()) {
                val idx = BASE32.indexOf(c)
                if (idx < 0) return null
                buffer = (buffer shl 5) or idx
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    bitsLeft -= 8
                    result.add(((buffer shr bitsLeft) and 0xFF).toByte())
                }
            }
            result.toByteArray()
        } catch (e: Exception) { null }
    }
}
