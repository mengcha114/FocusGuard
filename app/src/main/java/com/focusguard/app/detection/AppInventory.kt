package com.focusguard.app.detection

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * 已安装应用清单。
 *
 * 用户在界面上选应用时不应该手输包名，这里统一提供可选列表。
 * 只列出「有启动图标」的应用——用户能点开的才有必要管控，
 * 纯后台服务、系统组件列出来只是噪音。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    val category: AppCategory
)

object AppInventory {

    private const val TAG = "AppInventory"

    @Volatile
    private var cache: List<InstalledApp>? = null

    /**
     * 获取可启动的应用列表，按名称排序，排除自身。
     *
     * @param includeSystem 是否包含系统应用，默认不含（列表会短很多）
     * @param forceRefresh 忽略缓存重新扫描
     */
    fun listLaunchableApps(
        context: Context,
        categoryStore: AppCategoryStore? = null,
        includeSystem: Boolean = false,
        forceRefresh: Boolean = false
    ): List<InstalledApp> {
        val cached = cache
        if (!forceRefresh && cached != null) {
            return if (includeSystem) cached else cached.filter { !it.isSystem }
        }

        val pm = context.packageManager
        val result = mutableListOf<InstalledApp>()

        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launcherIntent, 0)

            val seen = mutableSetOf<String>()
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                if (!seen.add(pkg)) continue

                val appInfo = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    continue
                }

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                val label = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    pkg
                }

                val icon = try {
                    pm.getApplicationIcon(appInfo)
                } catch (e: Exception) {
                    null
                }

                result += InstalledApp(
                    packageName = pkg,
                    label = label,
                    icon = icon,
                    isSystem = isSystem,
                    category = AppClassifier.resolveCategory(context, pkg, categoryStore)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "扫描应用列表失败：${e.message}")
        }

        val sorted = result.sortedBy { it.label }
        cache = sorted
        return if (includeSystem) sorted else sorted.filter { !it.isSystem }
    }

    /** 单个包名的显示名，取不到时返回包名本身。 */
    fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    fun iconOf(context: Context, packageName: String): Drawable? = try {
        context.packageManager.getApplicationIcon(packageName)
    } catch (e: Exception) {
        null
    }

    fun invalidate() {
        cache = null
    }
}
