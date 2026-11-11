package com.vybra.music.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

/** Ventana principal (para que los diálogos queden centrados sobre ella). */
var mainFrame: Frame? = null

object FileDialogs {
    /** Diálogo nativo de Windows para abrir uno o varios archivos. */
    fun openFiles(title: String, extensions: List<String>?, multiple: Boolean): List<File> {
        val dialog = FileDialog(mainFrame, title, FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        if (extensions != null) {
            // En Windows el filtro se aplica con un patrón en el nombre.
            dialog.file = extensions.joinToString(";") { "*.$it" }
            dialog.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
        }
        dialog.isVisible = true
        return dialog.files?.toList().orEmpty()
    }

    /** Selector de carpetas. */
    fun chooseFolder(title: String, start: File? = null): File? {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val chooser = JFileChooser(start)
        chooser.dialogTitle = title
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        return if (chooser.showOpenDialog(mainFrame) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    /** Abre una carpeta en el Explorador de Windows. */
    fun reveal(file: File) {
        runCatching {
            if (file.isFile) ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(file)
        }
    }
}

/** Sustituto de los "launchers" de Android: abre el diálogo al llamar a [launch]. */
class FilePicker(private val open: () -> Unit) {
    fun launch(@Suppress("UNUSED_PARAMETER") vararg ignored: Any?) = open()
}

@Composable
fun rememberOpenFile(title: String, extensions: List<String>?, onResult: (File?) -> Unit): FilePicker {
    val callback = rememberUpdatedState(onResult)
    return remember(title) {
        FilePicker { callback.value(FileDialogs.openFiles(title, extensions, multiple = false).firstOrNull()) }
    }
}

@Composable
fun rememberOpenFiles(title: String, extensions: List<String>?, onResult: (List<File>) -> Unit): FilePicker {
    val callback = rememberUpdatedState(onResult)
    return remember(title) {
        FilePicker { callback.value(FileDialogs.openFiles(title, extensions, multiple = true)) }
    }
}

val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "bmp", "gif")
val LYRICS_EXTENSIONS = listOf("lrc", "txt", "elrc")
