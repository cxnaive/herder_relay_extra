package dev.herdr.companion

import com.termux.terminal.WcWidth
import kotlin.math.floor

/** Snapshot sizing uses terminal cells, not UTF-16 length or a second set of Paint metrics. */
object TerminalLayout {
    private val escapes = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B\\[[0-?]*[ -/]*[@-~]")
    /** Desktop TUIs paint their prompt by padding rows with colored spaces.
     * Those cells are layout, not scrollback text: never wrap them into phone-sized rows.
     * Retain SGR/OSC tokens after trimming so colors/hyperlinks do not leak into the next line.
     */
    fun phoneSnapshot(content: String): String = content.replace("\r\n", "\n").split('\n').joinToString("\n") { line ->
        val controls = escapes.findAll(line).toList()
        var position = 0
        var lastText = -1
        fun inspect(end: Int) {
            for (index in position until end) if (line[index] != ' ' && line[index] != '\t' && line[index] != '\r') lastText = index
        }
        for (token in controls) { inspect(token.range.first); position = token.range.last + 1 }
        inspect(line.length)
        buildString {
            position = 0
            for (token in controls) {
                if (position <= lastText) append(line, position, minOf(token.range.first, lastText + 1))
                append(token.value)
                position = token.range.last + 1
            }
            if (position <= lastText) append(line, position, lastText + 1)
        }
    }
    fun columns(viewport: Int, padding: Int, cellWidth: Float): Int =
        floor((viewport - padding).coerceAtLeast(1) / cellWidth.coerceAtLeast(1f)).toInt().coerceIn(20, 512)
    fun lineCells(line: String): Int {
        var cells = 0
        escapes.replace(line, "").codePoints().forEach { cp ->
            cells += if (cp == 9) 8 - cells % 8 else WcWidth.width(cp).coerceAtLeast(0)
        }
        return cells
    }
    fun sourceColumns(content: String): Int = content.lineSequence().maxOfOrNull(::lineCells)?.coerceIn(20, 512) ?: 80
    fun rows(content: String, columns: Int): Int {
        val width = columns.coerceAtLeast(2)
        return (content.lineSequence().sumOf { line ->
            var rows = 1; var column = 0
            escapes.replace(line, "").codePoints().forEach { cp ->
                val cells = if (cp == 9) 8 - column % 8 else WcWidth.width(cp).coerceAtLeast(0)
                if (cells > 0) {
                    // A CJK/emoji glyph cannot be split across the last cell of a row.
                    if (column + cells > width) { rows++; column = 0 }
                    column += cells
                }
            }
            rows
        } + 1).coerceIn(2, 20000)
    }
    fun scrollY(previousY: Int, previousHeight: Int, newHeight: Int, viewport: Int, follow: Boolean, older: Boolean): Int {
        val end = (newHeight - viewport).coerceAtLeast(0)
        return when { follow -> end; older -> previousY + newHeight - previousHeight; else -> previousY }.coerceIn(0, end)
    }
}
