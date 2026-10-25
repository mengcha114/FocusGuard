package com.focusguard.app.challenge

import com.focusguard.app.BuildConfig

/**
 * 答题模式。由构建版本决定（见 app/build.gradle.kts 的 productFlavors）：
 * - [EDU] 学段版：按学段与选科出真题库的题，需要选择年级；
 * - [GENERAL] 通用版：程序生成的繁复多步计算题，不需要选年级。
 */
enum class ChallengeMode {
    EDU, GENERAL;

    companion object {
        /** 单元测试覆盖用：非空时优先于 BuildConfig。 */
        @Volatile
        private var testOverride: ChallengeMode? = null

        /** 仅供测试：强制模式；传 null 恢复按构建版本判定。 */
        internal fun setForTest(mode: ChallengeMode?) {
            testOverride = mode
        }

        /**
         * 当前版本的模式。
         *
         * 直接读 `BuildConfig.CHALLENGE_MODE`：release 构建会做 R8 混淆，
         * 用 Class.forName 反射读取会在混淆后失败并静默退回通用版。
         */
        fun current(): ChallengeMode = testOverride ?: if (
            BuildConfig.CHALLENGE_MODE.equals("edu", ignoreCase = true)
        ) EDU else GENERAL

        /** 是否需要在首次进入时强制选择年级（仅学段版）。 */
        fun needsGrade(): Boolean = current() == EDU
    }
}
