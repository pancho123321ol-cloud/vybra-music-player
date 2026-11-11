package com.vybra.music

import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Registro sencillo con la misma forma que android.util.Log (consola + archivo en la carpeta de datos). */
object Log {
    const val DEBUG = 3
    const val INFO = 4
    private val debug = System.getProperty("vybra.debug") != null
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private var writer: PrintWriter? = null

    fun init(dir: File) {
        runCatching {
            val file = File(dir, "vybra.log")
            if (file.length() > 2_000_000) file.delete()
            writer = PrintWriter(file.outputStream().let { java.io.FileOutputStream(file, true) }.bufferedWriter(), true)
        }
    }

    fun isLoggable(@Suppress("UNUSED_PARAMETER") tag: String, level: Int): Boolean = level > DEBUG || debug

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = "${LocalDateTime.now().format(time)} $level/$tag: $msg"
        println(line)
        t?.printStackTrace(System.out)
        writer?.let { w ->
            synchronized(w) {
                w.println(line)
                t?.printStackTrace(w)
            }
        }
    }

    fun d(tag: String, msg: String) { if (debug) write("D", tag, msg, null) }
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)
}
