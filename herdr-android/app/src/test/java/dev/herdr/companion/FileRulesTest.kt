package dev.herdr.companion

import org.junit.Assert.*
import org.junit.Test

class FileRulesTest {
    @Test fun exportNamesCannotEscapeShareDirectory() {
        assertEquals("passwd", FileRules.safeName("../../etc/passwd"))
        assertEquals("private.txt", FileRules.safeName("C:\\tmp\\private.txt"))
        assertEquals("download", FileRules.safeName("/"))
        assertFalse(FileRules.safeName("a\n\u0000b.txt").contains('\u0000'))
    }
    @Test fun handlesUnicodeAndUppercaseExtensions() {
        assertTrue(FileRules.isMarkdown("文档/README.MD"))
        assertFalse(FileRules.isMarkdown("file.pdf"))
        assertEquals("项目/文档", FileRules.parent("项目/文档/说明.md"))
    }
}
