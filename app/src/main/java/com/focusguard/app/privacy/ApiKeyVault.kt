package com.focusguard.app.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API 密钥加密保险库。
 *
 * ## 背景
 * 此前 API 密钥明文存放在 SharedPreferences（`focus_guard_settings.xml`），
 * 任何拿到备份文件或 root 读取权限的东西都能直接读走密钥。
 *
 * ## 方案：Android Keystore 内的 AES-256-GCM
 * - 密钥生成在 Keystore 中（硬件支持时不可导出），应用进程只能"用"它，
 *   永远读不到密钥本身；
 * - 密文（IV + ciphertext，Base64）存在独立的 SharedPreferences 文件里；
 * - 备份到云端/复制走 prefs 文件也解不出明文——Keystore 密钥不随备份迁移，
 *   换机恢复后解密失败按"未配置密钥"处理，用户重新输入即可。
 *
 * ## 失败兜底
 * 任何一步异常（Keystore 不可用、密文损坏、换机恢复）都返回失败/空串，
 * 上层表现为"未配置 API 密钥"→ 检测降级为本地判定，功能不中断、不崩溃。
 */
object ApiKeyVault {

    private const val TAG = "ApiKeyVault"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "focusguard_api_key_v1"
    private const val PREFS_NAME = "focus_guard_api_key_vault"
    private const val KEY_CIPHER = "api_key_cipher"

    /** AES-GCM 标准 IV 长度（12 字节）。 */
    private const val GCM_IV_LENGTH = 12

    /** GCM 认证标签长度（128 位）。 */
    private const val GCM_TAG_LENGTH_BITS = 128

    /** 加密明文并落盘。返回是否成功。 */
    fun encrypt(context: Context, plain: String): Boolean {
        return try {
            val key = getOrCreateKey() ?: return false
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + cipherText
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CIPHER, Base64.encodeToString(payload, Base64.NO_WRAP))
                .apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "加密 API 密钥失败：${e.message}")
            false
        }
    }

    /** 解密取回明文。无密文/解密失败返回空串。 */
    fun decrypt(context: Context): String {
        return try {
            val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CIPHER, null) ?: return ""
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_LENGTH) return ""
            val key = getOrCreateKey() ?: return ""
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // 换机恢复（Keystore 密钥不存在）、密文损坏等：按未配置处理
            Log.w(TAG, "解密 API 密钥失败（按未配置处理）：${e.message}")
            ""
        }
    }

    /** 清除已存储的密文。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CIPHER)
            .apply()
    }

    /** 从 Keystore 取密钥，不存在则创建。失败返回 null。 */
    private fun getOrCreateKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "获取/创建 Keystore 密钥失败：${e.message}")
            null
        }
    }
}
