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
        for (path in listOf("../secret.md", "%2e%2e/secret.md", "docs/%2e%2e/%2e%2e/secret.pdf", "/work/project-other/x.md", "file:///tmp/a.pdf", "https://example.com/paper.pdf", "javascript:alert(1)", "content://files/a.pdf", "//host/a.md", "%2f%2fhost/a.md", "a.md%00")) {
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
    @Test fun anyWorkspaceFileLinkOpensWithoutAnExtensionAllowlist() {
        for (path in listOf("results/archive.zip", "logs/run.jsonl", "data/table.csv", "src/main.py:42", "LICENSE", "file:///work/project/output/model.bin")) {
            val ref = ReviewReferences.local(path, "/work/project")
            assertNotNull(path, ref); assertEquals("file", ref!!.kind)
        }
        assertEquals(42, ReviewReferences.local("src/main.py:42", "/work/project")!!.line)
        assertNull(ReviewReferences.local("a%0afile", "/work/project"))
        assertNull(ReviewReferences.local("../private.zip", "/work/project"))
        assertNull(ReviewReferences.local("file://remote/work/project/a.zip", "/work/project"))
        val refs = ReviewReferences.documents(JSONObject().put("text", "[下载压缩包](output/archive.zip) [无后缀](LICENSE) `src/main.py:42` 普通路径 /work/project/output/result.csv\n`not a filename`\n```\n/work/project/hidden.bin\n```"), "/work/project")
        assertEquals(setOf("output/archive.zip", "LICENSE", "src/main.py", "output/result.csv"), refs.map { it.path }.toSet())
    }

    @Test fun releaseNamesAndRepositorySlugAreNotLocalFileCards() {
        // A release announcement has code spans, but it contains no file links.
        val text = "已提交并发布到 `cxnaive/herder_relay_extra`：\n" +
            "- **Commit：`db8a95e`**，已推送。\n" +
            "- **Android：`android-v0.2.17`**，已设为 Latest。\n" +
            "- **服务端：`native-v0.23.5-codexfix.11`**，包含安装包。"
        for (unrestricted in listOf(false, true)) {
            assertEquals(emptyList<String>(), ReviewReferences.documents(JSONObject().put("text", text), "/work/project", unrestricted).map { it.path })
        }
    }
    @Test fun nakedArtifactNamesWithKnownExtensionStillNeedAnExplicitPathOrLink() {
        val text = "附件 `Herdr-Pocket-0.2.17-debug.apk`，另附 `SHA256SUMS.txt`。\n" +
            "[下载](</work/project/dist/Herdr-Pocket-0.2.17-debug.apk>)\n" +
            "路径 `dist/SHA256SUMS.txt`；配置 `.env`；阅读 `README.md`。"
        assertEquals(listOf("dist/Herdr-Pocket-0.2.17-debug.apk", "dist/SHA256SUMS.txt", ".env", "README.md"),
            ReviewReferences.documents(JSONObject().put("text", text), "/work/project").map { it.path })
    }

    @Test fun automaticPathsStayConservativeButExplicitLinksKeepAllFileTypes() {
        val text = "`npm run scripts/build.js` `model/vendor` `v1.2.3` `0.23.5+codexfix.11` `WorkspaceBinding.fromAgent`\n" +
            "`./bin/tool` `/work/project/data/report final.csv` `docs/custom.ext999` `docs/LICENSE` `docs/note.md#L4`\n" +
            "[实际文件](android-v0.2.17) [无后缀](vendor/tool) [文件名](report.pdf)"
        assertEquals(listOf("bin/tool", "data/report final.csv", "docs/custom.ext999", "docs/LICENSE", "docs/note.md", "android-v0.2.17", "vendor/tool", "report.pdf"),
            ReviewReferences.documents(JSONObject().put("text", text), "/work/project").map { it.path })
        val manyTags = (1..12).joinToString(" ") { "`release-v0.2.$it`" }
        assertEquals(listOf("output/download.zip"), ReviewReferences.documents(JSONObject().put("text", "$manyTags [下载](output/download.zip)"), "/work/project").map { it.path })
    }

}
