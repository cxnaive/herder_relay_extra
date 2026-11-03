package dev.herdr.companion

import org.junit.Assert.*
import org.junit.Test

class TerminalSnapshotTest {
    private val esc = "\u001b["
    @Test fun desktop158ColumnPromptDoesNotBecomeAHugeGrayInputBlock() {
        val blank = "${esc}48;2;41;41;41m" + " ".repeat(158) + "${esc}0m"
        val prompt = "${esc}48;2;41;41;41m› Ask Codex to do anything" + " ".repeat(132) + "${esc}0m"
        val raw = "$blank\n$prompt\n$blank"
        val mobile = TerminalLayout.phoneSnapshot(raw)
        assertTrue(TerminalLayout.rows(raw, 46) >= 13)
        assertEquals(4, TerminalLayout.rows(mobile, 46))
        assertTrue(mobile.contains("› Ask Codex to do anything"))
        assertTrue(mobile.endsWith("${esc}0m"))
    }
    @Test fun keepsInternalWhitespaceIndentationAndChinese() {
        val raw = "    val 表格 = \"a  b\"   ${esc}0m"
        assertEquals("    val 表格 = \"a  b\"${esc}0m", TerminalLayout.phoneSnapshot(raw))
    }
    @Test fun preservesTrailingColorAndHyperlinkClosures() {
        val link = "\u001b]8;;https://example.com\u001b\\link   \u001b]8;;\u001b\\${esc}0m"
        assertEquals("\u001b]8;;https://example.com\u001b\\link\u001b]8;;\u001b\\${esc}0m", TerminalLayout.phoneSnapshot(link))
    }
    @Test fun whitespaceBetweenTrailingResetsCannotSneakBackIntoTheDocument() {
        assertEquals("text${esc}0m${esc}49m", TerminalLayout.phoneSnapshot("text  ${esc}0m    ${esc}49m   "))
        assertEquals("${esc}0m\n", TerminalLayout.phoneSnapshot("   ${esc}0m\r\n"))
    }
    @Test fun emptySnapshotAndAlreadyUnpaddedLogsAreStable() {
        assertEquals("", TerminalLayout.phoneSnapshot(""))
        assertEquals("line1\nline2", TerminalLayout.phoneSnapshot("line1\nline2"))
    }
}
