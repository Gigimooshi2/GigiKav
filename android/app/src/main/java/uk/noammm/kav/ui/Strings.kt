package uk.noammm.kav.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Lang(val code: String, val label: String) {
    EN("en", "English"),
    HE("he", "עברית"),
}

object T {
    var lang by mutableStateOf(Lang.EN)

    var wanted by mutableStateOf<Lang?>(null)
        private set

    fun switchTo(l: Lang) { wanted = if (l == lang) null else l }

    fun commit() { wanted?.let { lang = it; wanted = null } }

    operator fun invoke(en: String, he: String): String = if (lang == Lang.HE) he else en

    val rtl: Boolean get() = lang == Lang.HE

    val onward: String get() = if (rtl) "‹" else "›"
    val backward: String get() = if (rtl) "›" else "‹"

    fun ltr(s: String): String = if (rtl) "\u2066" + s + "\u2069" else s

    val locale: java.util.Locale
        get() = java.util.Locale.forLanguageTag(if (rtl) "he-IL" else "en-GB")
}

fun mirrorX(f: Float): Float = if (T.rtl) 1f - f else f
