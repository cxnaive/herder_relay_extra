package dev.herdr.companion

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexFileCitationsTest {
    private val cwd = "/work/project"
    private val parser = Parser.builder().also(CodexFileCitations::configure).build()
    private fun links(node: Node): List<String> {
        val links = mutableListOf<String>()
        node.accept(object : AbstractVisitor() { override fun visit(link: Link) { links += link.destination; super.visit(link) } })
        return links
    }
    private fun docs(text: String) = ReviewReferences.documents(JSONObject().put("text", text), cwd)
    private fun citation(path: String) = ":codex-file-citation{path=\"$path\" purpose=\"output\"}"

    @Test fun lastWriterTurnDirectiveFormatProducesBothPdfReferences() {
        // Same two standalone directives as writer-1's last turn, with a portable fixture root.
        val text = """
            **成本表单页预览：**

            :codex-file-citation{path="/work/project/output/pdf/GT-VLM-Inference-Cost-Preview.pdf" purpose="output"}

            **完整 B 版，首页名称已同步：**

            :codex-file-citation{path="/work/project/output/pdf/GT-VLM-CVPR2027-Teaser-B.pdf" purpose="output"}
        """.trimIndent()
        assertTrue("Baseline CommonMark never recognized these directives", links(Parser.builder().build().parse(text)).isEmpty())
        assertEquals(listOf("output/pdf/GT-VLM-Inference-Cost-Preview.pdf", "output/pdf/GT-VLM-CVPR2027-Teaser-B.pdf"), docs(text).map { it.path })
        assertEquals(2, links(parser.parse(text)).size)
        val literal = StringBuilder()
        parser.parse(text).accept(object : AbstractVisitor() { override fun visit(text: Text) { literal.append(text.literal) } })
        assertFalse("Raw directive leaked into display", literal.contains(":codex-file-citation"))
        assertFalse(literal.contains("HERDRFILECITATIONTOKEN"))
    }
    @Test fun preservesRawQuotedPathsAndDoesNotInterpretThemAsMarkdown() {
        val paths = listOf("/work/project/docs/a_b_c.md", "/work/project/docs/[results](draft).pdf", "/work/project/docs/*cost* `v2`.pdf", "/work/project/docs/a}b.pdf", "/work/project/docs/a\"b.pdf")
        for (path in paths) {
            val raw = path.replace("\\", "\\\\").replace("\"", "\\\"")
            val text = "结果 **${citation(raw)}** 已生成。"
            assertEquals(listOf(path), links(parser.parse(text)))
            assertEquals(path.removePrefix("$cwd/"), docs(text).single().path)
        }
        assertEquals("docs/README.md", docs(":codex-file-citation{purpose='output' path='docs/README.md'}").single().path)
    }
    @Test fun codeExamplesAndEscapedDirectivesRemainLiteral() {
        val directive = citation("/work/project/paper.pdf")
        for (source in listOf("`$directive`", "``$directive``", "```text\n$directive\n```", "    $directive", "\\$directive")) {
            assertTrue(source, links(parser.parse(source)).isEmpty())
            assertTrue(source, docs(source).isEmpty())
        }
        val node = parser.parse("`$directive`")
        var code = ""
        node.accept(object : AbstractVisitor() { override fun visit(node: Code) { code = node.literal } })
        assertEquals(directive, code)
    }
    @Test fun rejectsMalformedAttributesAndKeepsWorkspaceSecurityChecks() {
        for (source in listOf(":codex-file-citation{purpose=\"output\"}", ":codex-file-citation{path=\"a.pdf\" path=\"b.pdf\"}", ":codex-file-citation{path=a.pdf}", ":codex-file-citation{path=\"bad\npath.pdf\"}", ":codex-file-citation{path=\"no close.pdf}", ":codex-file-citation{path=\"x.pdf\"purpose=\"output\"}")) {
            assertTrue(source, links(parser.parse(source)).isEmpty())
            assertTrue(source, docs(source).isEmpty())
        }
        for (path in listOf("../private.pdf", "/tmp/private.pdf", "https://example.com/paper.pdf", "%2e%2e/private.pdf", "/work/project-other/private.md")) {
            assertTrue(path, docs(citation(path)).isEmpty())
        }
    }
    @Test fun deduplicatesNormalLinksAndSupportsPdfPageOrMarkdownLine() {
        val text = citation("docs/paper.pdf#page=3") + "\n\n[重复](docs/paper.pdf#page=3)\n\n" + citation("docs/note.md#L12")
        val refs = docs(text)
        assertEquals(2, refs.size); assertEquals(3, refs.first().page); assertEquals(12, refs.last().line)
        assertEquals(8, docs((1..20).joinToString("\n\n") { citation("$it.pdf") }).size)
    }
    @Test fun neverCreatesNestedLinksOrLeavesInternalTokensInHtml() {
        val directive = citation("/work/project/paper.pdf")
        val source = "[$directive](note.md) <span data-file='$directive'>test</span>"
        val node = parser.parse(source)
        assertEquals(listOf("note.md"), links(node))
        var literals = ""
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) { literals += text.literal }
            override fun visit(html: HtmlInline) { literals += html.literal }
        })
        assertTrue(literals.contains(directive)); assertFalse(literals.contains("HERDRFILECITATIONTOKEN"))
    }
    @Test fun ordinaryMarkdownAndDelimitersContinueToWork() {
        val text = "# Report\n\n[Original](docs/report.md) **bold** `inline`\n\nHERDRFILECITATIONTOKEN literal " + citation("docs/paper.pdf")
        assertEquals(listOf("docs/report.md", "docs/paper.pdf"), links(parser.parse(text)))
    }
}
