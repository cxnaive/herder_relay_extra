package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkspaceDirectoryPageTest {
    private fun row(path: String) = JSONObject().put("path", path).put("name", path.substringAfterLast('/')).put("kind", "file")
    private fun page(path: String, rows: JSONArray, cursor: String = "", total: Int = rows.length()) = JSONObject()
        .put("root", "/fixture/project").put("path", path).put("entries", rows).put("next_cursor", cursor).put("revision", "revision-a").put("total", total).put("skipped", 0)
    @Test fun pagesAppendDirectChildrenWithoutReplacingEarlierSiblings() {
        val first = WorkspaceDirectoryPage.accept(page("docs", JSONArray().put(row("docs/a.md")), "next", 2), "docs")
        val second = WorkspaceDirectoryPage.accept(page("docs", JSONArray().put(row("docs/b.md")), "", 2), "docs", first)
        assertEquals(2, second.entries.length()); assertEquals("docs/a.md", second.entries.getJSONObject(0).getString("path"))
        assertEquals("docs/b.md", second.entries.getJSONObject(1).getString("path")); assertEquals("", second.cursor)
        assertEquals(1, first.entries.length())
    }
    @Test fun wrongDirectoryRootRevisionOrDuplicatePageCannotOverwriteCurrentListing() {
        val first = WorkspaceDirectoryPage.accept(page("docs", JSONArray().put(row("docs/a.md")), "next", 2), "docs")
        val valid = page("docs", JSONArray().put(row("docs/b.md")), "", 2)
        for (bad in listOf(JSONObject(valid.toString()).put("path", "scripts"), JSONObject(valid.toString()).put("root", "/other"),
            JSONObject(valid.toString()).put("revision", "new"), page("docs", JSONArray().put(row("docs/a.md")), "", 2))) {
            assertThrows(IllegalArgumentException::class.java) { WorkspaceDirectoryPage.accept(bad, "docs", first) }
        }
        assertEquals(1, first.entries.length())
    }
    @Test fun recursiveChildrenAndUnsafePathsAreRejected() {
        for (path in listOf("docs/sub/a.md", "other/a.md", "../a.md", "/docs/a.md", "docs/./a.md")) {
            assertThrows(IllegalArgumentException::class.java) { WorkspaceDirectoryPage.accept(page("docs", JSONArray().put(row(path))), "docs") }
        }
    }
    @Test fun emptyDirectoryAndIncompletePageAreDifferentStates() {
        assertEquals(0, WorkspaceDirectoryPage.accept(page("empty", JSONArray()), "empty").total)
        assertThrows(IllegalArgumentException::class.java) { WorkspaceDirectoryPage.accept(page("empty", JSONArray(), total = 8), "empty") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceDirectoryPage.accept(page("empty", JSONArray(), "next", 8), "empty") }
    }
}
