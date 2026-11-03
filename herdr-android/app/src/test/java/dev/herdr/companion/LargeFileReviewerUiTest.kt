package dev.herdr.companion

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeFileReviewerUiTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun oversizedLinkedFileOpensDetailsAndBothActionsAreClickable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val actions = mutableListOf<Pair<String,Boolean>>(); val paths = mutableListOf<String>()
        val reviewer = DocumentReviewer(activity, "/work/project", { true }, { path, share -> actions += path to share }) { path, done ->
            paths += path
            done(JSONObject().put("path", path).put("kind", "pdf").put("size", 48L*1024*1024).put("preview_available", false)
                .put("preview_reason", "文件超过 12 MiB 预览限制；仍可下载或分享原文件"), null)
        }
        try {
            reviewer.openLink("/work/project/output/report.pdf")
            assertTrue(reviewer.isOpen); assertEquals(listOf("output/report.pdf"), paths)
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            shadowOf(Looper.getMainLooper()).idle()
            val texts = views(root).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.any { it.contains("48.0 MB") }); assertTrue(texts.any { it.contains("超过 12 MiB") })
            views(root).filterIsInstance<Button>().first { it.text == "下载原文件" }.performClick()
            views(root).filterIsInstance<Button>().first { it.text == "分享" }.performClick()
            assertEquals(listOf("output/report.pdf" to false, "output/report.pdf" to true), actions)
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY)); root.layout(0,0,800,1600)
            val bitmap = Bitmap.createBitmap(800,1600,Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
            File("build/reports/file-access").mkdirs(); File("build/reports/file-access/large-file-details.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        } finally { reviewer.close(); controller.pause().stop().destroy() }
    }
    @Test fun optInAllowsOutsideLinksButDefaultStillDeniesThem() {
        assertNull(ReviewReferences.local("/tmp/output.zip", "/work/project"))
        assertEquals("tmp/output.zip", ReviewReferences.local("/tmp/output.zip", "/work/project", unrestricted = true)!!.path)
        assertEquals("work/project/outputs/a.csv", ReviewReferences.local("outputs/a.csv", "/work/project", unrestricted = true)!!.path)
        assertEquals("work/other/report.pdf", ReviewReferences.local("../../other/report.pdf", "/work/project", "work/project/docs/readme.md", unrestricted = true)!!.path)
        for (path in listOf("https://site/a.zip", "content://files/a.pdf", "file://remote/tmp/a.csv", "javascript:alert(1)")) assertNull(ReviewReferences.local(path, "/work/project", unrestricted = true))
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get(); var requested = ""
        val reviewer = DocumentReviewer(activity, "/work/project", { true }, unrestricted = true) { path, done -> requested = path; done(JSONObject().put("preview_available", false), null) }
        try { reviewer.openLink("/tmp/output.zip"); assertEquals("tmp/output.zip", requested); assertTrue(reviewer.isOpen) } finally { reviewer.close(); controller.pause().stop().destroy() }
    }
}
