package com.vybra.music.platform

import java.util.concurrent.TimeUnit

/** Lectura de preferencias de apariencia de Windows (color de énfasis y modo oscuro). */
object WindowsTheme {
    private val accent: Int? by lazy { readAccent() }

    /** Color de énfasis de Windows como ARGB, o null si no se puede leer. */
    fun accentColor(): Int? = accent

    /** true si Windows usa el modo oscuro para las apps. */
    val appsUseDarkTheme: Boolean? by lazy {
        regDword("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "AppsUseLightTheme")?.let { it == 0L }
    }

    private fun readAccent(): Int? {
        // DWM guarda el color como 0xAABBGGRR.
        val abgr = regDword("HKCU\\Software\\Microsoft\\Windows\\DWM", "AccentColor") ?: return null
        val r = (abgr and 0xFF).toInt()
        val g = ((abgr shr 8) and 0xFF).toInt()
        val b = ((abgr shr 16) and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun regDword(key: String, value: String): Long? = runCatching {
        if (!System.getProperty("os.name").lowercase().contains("win")) return null
        val p = ProcessBuilder("reg", "query", key, "/v", value).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(3, TimeUnit.SECONDS)
        Regex("REG_DWORD\\s+0x([0-9a-fA-F]+)").find(out)?.groupValues?.get(1)?.toLong(16)
    }.getOrNull()
}
