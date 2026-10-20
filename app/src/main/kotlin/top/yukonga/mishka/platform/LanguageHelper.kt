package top.yukonga.mishka.platform

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 应用内语言切换。支持的语言与 res/values-* 限定符一一对应。
 *
 * API 33+ 走 LocaleManager.setApplicationLocales，由系统持久化并自动重建 Activity；
 * API 31-32 无系统级 per-app locale，只能把选择存进 PlatformStorage，在
 * Application / Activity 的 attachBaseContext 里手动 wrap locale，切换后调
 * Activity.recreate() 生效。
 */
object LanguageHelper {

    /** code 为 BCP 47 tag；空字符串表示跟随系统。nativeName 恒为该语言自称，不随界面语言变。 */
    data class Language(val code: String, val nativeName: String)

    val LANGUAGES = listOf(
        Language("", ""),
        Language("zh-CN", "简体中文"),
        Language("zh-TW", "繁體中文"),
        Language("en", "English"),
        Language("ru", "Русский"),
    )

    /** 当前生效的 code。33+ 以系统 LocaleManager 为准（用户可能在系统设置里改过），低版本读 prefs。 */
    fun getCurrentCode(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val tags = context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales?.toLanguageTags()
            if (!tags.isNullOrEmpty()) return tags
        }
        return context.getSharedPreferences("mishka_prefs", Context.MODE_PRIVATE)
            .getString(StorageKeys.APP_LANGUAGE, "") ?: ""
    }

    fun getCurrentIndex(context: Context): Int {
        val code = getCurrentCode(context)
        return LANGUAGES.indexOfFirst { it.code == code }.takeIf { it >= 0 } ?: 0
    }

    /**
     * 切换语言。33+ 交给系统（自动重建）；31-32 先落盘再 recreate，
     * 新 Activity 经 attachBaseContext 拿到 wrap 后的 context。
     */
    fun setLanguage(activity: Activity, code: String) {
        activity.getSharedPreferences("mishka_prefs", Context.MODE_PRIVATE)
            .edit().putString(StorageKeys.APP_LANGUAGE, code).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (code.isEmpty()) LocaleList.getEmpty() else LocaleList.forLanguageTags(code)
        } else {
            activity.recreate()
        }
    }

    /**
     * 供 Application / Activity.attachBaseContext 调用。33+ 系统已处理，直接返回原 context，
     * 避免两套机制打架；低版本按 prefs 里的选择 wrap locale。
     */
    fun wrapBaseContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val code = base.getSharedPreferences("mishka_prefs", Context.MODE_PRIVATE)
            .getString(StorageKeys.APP_LANGUAGE, "") ?: ""
        if (code.isEmpty()) return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
