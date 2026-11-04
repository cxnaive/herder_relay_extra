package dev.herdr.companion

import org.junit.Assert.*
import org.junit.Test

class ComputerNameTest {
    @Test fun trimsNamesAndAllowsChineseSpacesAndPunctuation() {
        assertEquals("家里 工作站（GPU）", ComputerName.validate("  家里 工作站（GPU）  "))
        assertEquals("x".repeat(48), ComputerName.validate("x".repeat(48)))
    }
    @Test fun rejectsEmptyLongAndMultilineNamesWithoutIncludingTheirContentsInErrors() {
        for (name in listOf("", " \t\n", "x".repeat(49), "家里\n工作站", "家里\r工作站", "a\tsecret", "a\u0000secret", "a\u2028secret", "a\u2029secret")) {
            val error = assertThrows(IllegalArgumentException::class.java) { ComputerName.validate(name) }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }
}
