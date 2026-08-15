package com.focusguard.app.privacy

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log

/**
 * 敏感应用隐私保护。
 *
 * ## 背景
 * 视觉检测必须把屏幕截图上传到用户配置的 AI 服务。对普通应用这是用户
 * 知情同意的行为；但银行、支付、密码管理、健康医疗这类应用承载的是
 * 高敏个人信息——账号、余额、密码、病历——这些内容**绝不应该**离开设备。
 *
 * ## 策略：宁放过，不误传
 * 只要前台应用命中敏感特征，本轮检测直接跳过（不截屏、不读屏幕文字、
 * 不发任何网络请求），判定结果记为中性 + 来源 PRIVACY_SKIP，不会触发
 * 任何执法动作。误判的代价只是"少检测一次"；漏判的代价是隐私泄露，
 * 两者权重完全不同，因此特征词宁可宽泛一些。
 *
 * ## 特征来源
 * 1. 内置包名/应用名特征片段（[builtinPackageHints] / [builtinLabelHints]）
 * 2. 系统声明的应用类目（Android 11+ 的 CATEGORY_FINANCE）
 * 3. 用户在设置里自定义的敏感应用列表（逗号分隔，按包名/应用名片段匹配）
 *
 * 核心判定 [isSensitive] 是纯函数（不依赖 Context），便于单元测试。
 */
object PrivacyGuard {

    private const val TAG = "PrivacyGuard"

    /** 内置敏感包名特征片段（小写匹配）。 */
    private val builtinPackageHints = listOf(
        // ── 银行 / 支付 / 金融 ──────────────────────────
        "bank", "banking", "unionpay", "alipay", "tenpay", "lakala",
        "paypal", "paytm", "cashapp", "venmo", "mybank", "dcep",
        "chinamworld", "cmbchina", "icbc", "abchina", "ccb", "psbc",
        "cmbc", "spdb", "cebbank", "citibank", "hsbc", "chase",
        "wellsfargo", "stock", "broker", "securities", "eastmoney",
        "xueqiu", "hexin", "invest", "fund",
        // ── 密码管理 / 双因素认证 ──────────────────────
        "bitwarden", "keepass", "lastpass", "1password", "onepassword",
        "authy", "authenticator", "googleauth", "passwordmanager",
        "keychain", "vault",
        // ── 健康医疗 ────────────────────────────────────
        "health", "hospital", "clinic", "medical", "medication",
        "pharmacy", "dxy",
        // ── 邮箱 / 通讯 ─────────────────────────────────
        "gmail", "outlook", "hotmail", "thunderbird", "protonmail",
        // ── 私密相册 / 图库 ────────────────────────────
        "gallery", "photos"
    )

    /** 内置敏感应用名特征（小写匹配，中文应用名）。 */
    private val builtinLabelHints = listOf(
        "银行", "网银", "支付", "钱包", "理财", "证券", "保险",
        "基金", "股票", "贷款", "医保", "医院", "医疗", "健康",
        "挂号", "问诊", "病历", "邮箱", "邮件", "密码", "密保",
        "相册", "图库", "云盘照片", "身份验证", "动态口令"
    )

    /**
     * 判定前台应用是否敏感。纯函数，无 IO。
     *
     * @param packageName 前台应用包名
     * @param label 前台应用显示名
     * @param userList 用户自定义敏感应用列表（逗号/换行分隔，可为空）
     */
    fun isSensitive(packageName: String, label: String, userList: String): Boolean {
        if (packageName.isBlank() && label.isBlank()) return false
        val pkg = packageName.lowercase()
        val lab = label.lowercase()

        if (builtinPackageHints.any { pkg.contains(it) }) {
            Log.d(TAG, "$packageName 命中内置敏感特征，隐私保护跳过")
            return true
        }
        if (builtinLabelHints.any { lab.contains(it) }) {
            Log.d(TAG, "$label 命中内置敏感特征，隐私保护跳过")
            return true
        }
        if (userList.isNotBlank()) {
            val hit = userList.split(',', '，', '\n')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .any { pkg.contains(it) || lab.contains(it) }
            if (hit) {
                Log.d(TAG, "$packageName 命中用户自定义敏感列表，隐私保护跳过")
                return true
            }
        }
        return false
    }

    /**
     * 系统声明的敏感类目：Android 11+ 应用可声明 CATEGORY_FINANCE
     * （银行/支付类）。这是开发者自己声明的属性，比关键词猜测更可靠。
     */
    fun isFinanceCategory(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (packageName.isBlank()) return false
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            info.category == ApplicationInfo.CATEGORY_FINANCE
        } catch (e: Exception) {
            false
        }
    }
}
