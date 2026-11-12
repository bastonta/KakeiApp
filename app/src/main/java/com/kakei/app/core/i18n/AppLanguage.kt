package com.kakei.app.core.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

/**
 * Interface languages shipped in `res/values` (EN) and `res/values-ru` (RU). The selected one is
 * applied as the per-app locale, so `Resources` resolve strings in it.
 */
enum class AppLanguage(val code: String, val displayName: String) {
    RU("ru", "Русский"),
    EN("en", "English");

    val locale: Locale get() = Locale.forLanguageTag(code)

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: RU
    }
}

/** Interface language these resources were resolved for. */
val Resources.appLanguage: AppLanguage
    get() = AppLanguage.fromCode(configuration.locales[0].language)

/**
 * Context whose resources resolve strings in [language]. Background code (workers, the
 * repository) uses it instead of relying on the system per-app locale, which the framework does
 * not always apply to the application context outside an activity.
 */
fun Context.withAppLanguage(language: AppLanguage): Context {
    val config = Configuration(resources.configuration)
    config.setLocales(LocaleList(language.locale))
    return createConfigurationContext(config)
}
