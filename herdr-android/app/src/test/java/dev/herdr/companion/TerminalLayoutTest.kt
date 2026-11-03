package dev.herdr.companion

import org.junit.Assert.*
import org.junit.Test

class TerminalLayoutTest {
    @Test fun phoneColumnsUseFontMetricsWithoutStretching() {
        assertEquals(50, TerminalLayout.columns(720, 20, 14f))
        assertEquals(20, TerminalLayout.columns(10, 20, 14f))
    }
    @Test fun ansiEscapesDoNotOccupyCellsAndChineseUsesTwo() {
        assertEquals(5, TerminalLayout.lineCells("\u001B[32m中文A\u001B[0m"))
        assertEquals(1, TerminalLayout.lineCells("e\u0301"))
        assertEquals(9, TerminalLayout.lineCells("A\tB"))
        assertEquals(4, TerminalLayout.lineCells("\u001B]0;title\u0007test"))
    }
    @Test fun wrappedHistoryGetsEnoughRowsAndNoArtificial24RowGap() {
        assertEquals(2, TerminalLayout.rows("hello", 40))
        assertEquals(4, TerminalLayout.rows("x".repeat(101), 40))
        assertEquals(4, TerminalLayout.rows("中".repeat(5), 5))
        assertEquals(1501, TerminalLayout.rows(List(1500) { "输出" }.joinToString("\n"), 40))
    }
    @Test fun refreshDoesNotJumpAwayFromWhatTheUserIsReading() {
        assertEquals(300, TerminalLayout.scrollY(300, 2000, 2400, 500, follow = false, older = false))
        assertEquals(700, TerminalLayout.scrollY(300, 2000, 2400, 500, follow = false, older = true))
        assertEquals(1900, TerminalLayout.scrollY(1500, 2000, 2400, 500, follow = true, older = false))
        assertEquals(0, TerminalLayout.scrollY(100, 1000, 200, 500, follow = false, older = false))
    }
}
