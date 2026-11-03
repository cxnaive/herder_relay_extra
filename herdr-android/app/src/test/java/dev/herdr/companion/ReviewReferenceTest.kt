package dev.herdr.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReviewReferenceTest {
    @Test fun recognizesCodexPdfPagesAndMarkdownLinesWithoutCrossingWorkspaces() {
        val pdf = ReviewReferences.local("/work/project/papers/3d.pdf#page=4", "/work/project")!!
        assertEquals("papers/3d.pdf", pdf.path); assertEquals(4, pdf.page)
        val md = ReviewReferences.local("README.md:12:3", "/work/project")!!
        assertEquals("README.md", md.path); assertEquals(12, md.line)
        assertEquals(19, ReviewReferences.local("notes.md#L19-L22", "/work/project")!!.line)
        assertEquals("paper (draft).pdf", ReviewReferences.local("paper%20(draft).pdf", "/work/project")!!.path)
        assertEquals("a+b.md", ReviewReferences.local("a+b.md", "/work/project")!!.path)
        assertEquals("notes.md", ReviewReferences.local("file:///work/project/notes.md#L3", "/work/project")!!.path)
    }
    @Test fun relativeLinksAndAnchorsResolveAgainstDocumentNotWorkspaceRoot() {
        assertEquals("docs/images/plot.png", ReviewReferences.local("images/plot.png", "/work/project", "docs/readme.md")!!.path)
        assertEquals("papers/main.pdf", ReviewReferences.local("../papers/main.pdf", "/work/project", "docs/readme.md")!!.path)
        assertEquals("docs/readme.md", ReviewReferences.local("#实验设置", "/work/project", "docs/readme.md")!!.path)
        assertEquals("实验设置", ReviewReferences.local("#%E5%AE%9E%E9%AA%8C%E8%AE%BE%E7%BD%AE", "/work/project", "docs/readme.md")!!.anchor)
    }
    @Test fun refusesRemoteUrlsEncodedTraversalAndUnsupportedSchemes() {
        for (path in listOf("../secret.md", "%2e%2e/secret.md", "docs/%2e%2e/%2e%2e/secret.pdf", "/work/project-other/x.md", "file:///tmp/a.pdf", "https://example.com/paper.pdf", "javascript:alert(1)", "content://files/a.pdf", "//host/a.md", "%2f%2fhost/a.md", "a.md%00", "a.txt")) {
            assertNull(path, ReviewReferences.local(path, "/work/project"))
        }
        assertNull(ReviewReferences.local("x.md", "relative-root"))
    }
    @Test fun extractsRealMarkdownLinksAndToolPathsOutsideTheFoldWithoutParsingCodeBlocks() {
        val entry = JSONObject().put("text", "[论文](<papers/paper (v2).pdf#page=3>)\n[笔记](docs/note.md#L2)\n`README.md`\n```\n[not a citation](secret.md)\n```\n[web](https://site/a.pdf)")
            .put("tools", JSONArray().put(JSONObject().put("input", """{"path":"/work/project/docs/note.md#L2"}""")))
        val refs = ReviewReferences.documents(entry, "/work/project")
        assertTrue(refs.any { it.path == "docs/note.md" && it.line == 2 }); assertTrue(refs.any { it.path == "README.md" })
        assertTrue(refs.any { it.path == "papers/paper (v2).pdf" && it.page == 3 }); assertFalse(refs.any { it.path == "secret.md" }); assertTrue(refs.size <= 3)
    }
    @Test fun markdownImagesStayInsideWorkspaceAndAreDeduplicated() {
        val images = ReviewReferences.markdownImages("![one](fig.png) ![two](fig.png) ![outside](../../private.png) ![web](https://site/a.png)", "/work/project", "docs/readme.md")
        assertEquals(listOf("docs/fig.png"), images.map { it.path })
    }
}
