package com.pattayacctv.viewer

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    private const val PREFS = "pattaya_cctv_prefs"
    private const val KEY_LANGUAGE = "app_language"

    const val LANG_TH = "th"
    const val LANG_EN = "en"
    const val LANG_ZH = "zh"

    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANG_TH)
            ?.takeIf { it in setOf(LANG_TH, LANG_EN, LANG_ZH) }
            ?: LANG_TH

    fun setLanguage(context: Context, language: String) {
        val safe = if (language in setOf(LANG_TH, LANG_EN, LANG_ZH)) language else LANG_TH
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, safe)
            .apply()
    }

    fun wrap(context: Context): Context {
        val locale = when (getLanguage(context)) {
            LANG_EN -> Locale.ENGLISH
            LANG_ZH -> Locale.SIMPLIFIED_CHINESE
            else -> Locale("th", "TH")
        }
        Locale.setDefault(locale)
        return context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
        )
    }
}
