package org.openplay.player

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object Locales {
    /** language tag to its own name. An empty tag means "follow the system". */
    val all: List<Pair<String, String>> = listOf(
        "en" to "English", "es" to "Español", "ar" to "العربية", "hi" to "हिन्दी", "bn" to "বাংলা",
        "pt" to "Português", "ru" to "Русский", "ja" to "日本語", "fr" to "Français",
        "de" to "Deutsch", "id" to "Bahasa Indonesia", "zh-CN" to "中文（简体）"
    )

    fun wrap(base: Context): Context {
        val tag = Prefs.str(base, "lang", "")
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }
}
