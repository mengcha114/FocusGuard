package com.focusguard.app.util

import kotlin.random.Random

/**
 * 凯撒密码工具（强度 3「朋友辅助」用）。
 *
 * 锁机方生成一个随机明文密码，用凯撒位移加密成密文，
 * 把「密文 + 偏移量」展示在锁机页；朋友的手机（或脑子）解密得到明文密码，
 * 在锁机页输入明文即可解锁。
 *
 * 只处理 A-Z / a-z（循环位移），数字和符号保持不变，便于人工心算。
 */
object CaesarHelper {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /**
     * 生成一组挑战：明文密码 + 加密后的密文。
     * 返回 Pair(密文, 偏移量)。
     */
    fun generateChallenge(): Pair<String, Int> {
        // 6 位大写字母 + 数字混合的明文密码
        val plain = buildString {
            repeat(6) {
                append(
                    if (Random.nextBoolean()) {
                        ALPHABET[Random.nextInt(ALPHABET.length)]
                    } else {
                        Random.nextInt(0, 10).toString()
                    }
                )
            }
        }
        val shift = Random.nextInt(1, 26)
        return encrypt(plain, shift) to shift
    }

    /** 凯撒加密：字母位移，数字/符号不动。 */
    fun encrypt(text: String, shift: Int): String {
        val s = ((shift % 26) + 26) % 26
        return text.map { ch ->
            when {
                ch in 'A'..'Z' -> ALPHABET[(ALPHABET.indexOf(ch) + s) % 26]
                ch in 'a'..'z' -> ('a' + (ch - 'a' + s) % 26)
                else -> ch
            }
        }.joinToString("")
    }

    /** 凯撒解密：字母逆位移。 */
    fun decrypt(text: String, shift: Int): String {
        val s = ((shift % 26) + 26) % 26
        return text.map { ch ->
            when {
                ch in 'A'..'Z' -> ALPHABET[(ALPHABET.indexOf(ch) - s + 26) % 26]
                ch in 'a'..'z' -> ('a' + (ch - 'a' - s + 26) % 26)
                else -> ch
            }
        }.joinToString("")
    }
}
