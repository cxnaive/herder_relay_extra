package dev.herdr.companion

import org.junit.Assert.*
import org.junit.Test

class TerminalKeyCommandTest {
    @Test fun canonicalHerdrKeyNamesDoNotUseTmuxAliases() {
        for((source,target) in mapOf("BSpace" to "Backspace","Escape" to "Esc","C-c" to "ctrl+c","C-w" to "ctrl+w","M-f" to "alt+f","S-Tab" to "shift+Tab","C-M-a" to "ctrl+alt+a","F12" to "f12","Enter" to "Enter")) {
            val command=TerminalKeyCommand.from(source)!!
            assertEquals("send_keys",command.type);assertEquals(target,command.payload.getJSONArray("keys").getString(0));assertFalse(command.payload.has("text"))
        }
    }
    @Test fun navigationKeysUseOnlyFixedVtSequencesWhenHerdrDoesNotParseTheirNames() {
        for((source,sequence) in mapOf("Home" to "\u001b[H","End" to "\u001b[F","Delete" to "\u001b[3~","DC" to "\u001b[3~","PPage" to "\u001b[5~","NPage" to "\u001b[6~","C-Home" to "\u001b[1;5H","M-End" to "\u001b[1;3F")) {
            val command=TerminalKeyCommand.from(source)!!;assertEquals("send_text",command.type);assertEquals(sequence,command.payload.getString("text"))
        }
        for (invalid in listOf("", "paste this", "\u001b[5~", "F99", "unknown+a", "C-/tmp/secret", "BSpace\nEnter")) assertNull(invalid,TerminalKeyCommand.from(invalid))
    }
}
