package dev.herdr.companion

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeReviewerUiTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun await(test: () -> Boolean) {
        repeat(300) { shadowOf(Looper.getMainLooper()).idle(); if (test()) return; Thread.sleep(10) }
        fail("Timed out waiting for native review rendering")
    }
    private fun bitmap(): Bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it); c.drawColor(0xfff5f8fb.toInt()); val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff123a50.toInt(); textSize = 54f }
        c.drawText("3D-VLM / VLA - reviewer fixture", 90f, 110f, p)
        p.strokeWidth = 3f
        for (i in 0..10) { p.color = 0xffd1dde3.toInt(); c.drawLine(100f, 230f + 55 * i, 1480f, 230f + 55 * i, p) }
        p.color = 0xff118b76.toInt(); p.strokeWidth = 9f
        for (i in 0..8) c.drawLine(140f + 145 * i, 740f - 43 * i, 285f + 145 * i, 697f - 43 * i, p)
        p.textSize = 30f; c.drawText("Synthetic illustration - not user research data", 100f, 920f, p)
    }
    private fun imageDoc(): JSONObject {
        val b = bitmap(); val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, out); b.recycle()
        return JSONObject().put("kind", "image").put("data", Base64.getEncoder().encodeToString(out.toByteArray()))
    }
    private fun draw(name: String, view: View, w: Int = 800, h: Int = 1600) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); view.layout(0, 0, w, h)
        view.viewTreeObserver.dispatchOnPreDraw(); shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); view.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Table row spans settle their text heights after the first draw, like real frames.
        repeat(3) {
            b.eraseColor(android.graphics.Color.TRANSPARENT); view.draw(Canvas(b)); shadowOf(Looper.getMainLooper()).idle()
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); view.layout(0, 0, w, h)
        }
        val dir = File("build/reports/ui-025").apply { mkdirs() }; File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
    }
    @Test fun realDoubleTapAndPinchEnlargeImageThenReset() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val view = ZoomImageView(activity); activity.setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.EXACTLY)); view.layout(0, 0, 800, 1400); view.setBitmap(bitmap())
        val start = SystemClock.uptimeMillis()
        fun tap(at: Long) {
            for ((action, t) in listOf(MotionEvent.ACTION_DOWN to at, MotionEvent.ACTION_UP to at + 40)) {
                val e = MotionEvent.obtain(at, t, action, 180f, 350f, 0); view.onTouchEvent(e); e.recycle()
            }
        }
        tap(start); tap(start + 130); assertTrue("Double tap did not zoom", view.viewport.zoom > 2f)
        view.fitScreen(); assertEquals(1f, view.viewport.zoom, 0f)
        fun touch(action: Int, elapsed: Long, distance: Float, two: Boolean = true) {
            val count = if (two) 2 else 1
            val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { x = 400 + (if (i == 0) -distance else distance); y = 700f; pressure = 1f; size = 1f } }
            val e = MotionEvent.obtain(start + 1000, start + 1000 + elapsed, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.onTouchEvent(e); e.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 0, 120f, false); touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40, 120f)
        for (i in 1..5) touch(MotionEvent.ACTION_MOVE, 40L + 40 * i, 120f + 52 * i)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 280, 380f)
        touch(MotionEvent.ACTION_UP, 320, 380f, false)
        assertTrue("Real two-pointer scale events did not zoom", view.viewport.zoom > 1.5f)
        val previousX = view.viewport.x
        for ((action, elapsed, x) in listOf(Triple(MotionEvent.ACTION_DOWN, 2000L, 400f), Triple(MotionEvent.ACTION_MOVE, 2040L, 230f), Triple(MotionEvent.ACTION_UP, 2080L, 230f))) {
            val event = MotionEvent.obtain(start + 2000, start + elapsed, action, x, 700f, 0); view.onTouchEvent(event); event.recycle()
        }
        assertTrue("Single-finger drag did not pan zoomed image", view.viewport.x < previousX)
        view.setBitmap(null); controller.pause().stop().destroy()
    }
    @Test fun fullScreenImageOpensOriginalAndKeepsZoomControls() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        var requested = ""
        val reviewer = DocumentReviewer(activity, "/work/project", { true }) { path, done -> requested = path; done(imageDoc(), null) }
        reviewer.open(ReviewReference("figures/results.png", "Results · 原图查看"))
        val dialog = ShadowDialog.getLatestDialog(); val root = dialog.window!!.decorView
        await { descendants(root).any { it is ZoomImageView } }
        assertEquals("figures/results.png", requested)
        val image = descendants(root).filterIsInstance<ZoomImageView>().single()
        assertTrue("Viewer accidentally reused a 768px thumbnail", image.viewport.imageWidth > 768)
        draw("reviewer-image-fit", root)
        descendants(root).first { it.contentDescription == "放大图片" }.performClick()
        assertTrue(image.viewport.zoom > 1f); image.changeZoom(3f); draw("reviewer-image-zoom", root)
        descendants(root).first { it.contentDescription == "还原图片缩放" }.performClick(); assertEquals(1f, image.viewport.zoom, 0f)
        reviewer.close(); assertFalse(dialog.isShowing); controller.pause().stop().destroy()
    }
    @Test @Config(sdk = [35]) fun fullScreenImageAlsoWorksOnAndroid15() { fullScreenImageOpensOriginalAndKeepsZoomControls() }

    @Test fun markdownLinksNavigateInsideReviewerAndStaleLoadsCannotReplaceDocument() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val requests = mutableListOf<String>(); val pending = mutableMapOf<String, (JSONObject?, String?) -> Unit>()
        val reviewer = DocumentReviewer(activity, "/work/project", { true }) { path, done -> requests += path; pending[path] = done }
        reviewer.open(ReviewReference("docs/readme.md"))
        pending.getValue("docs/readme.md")(JSONObject().put("kind", "text").put("text", "# 实验报告\n\n点击 [附录](appendix.md#L2) 查看。\n\n## 结果\n\n| 方法 | 得分 |\n|---|---|\n| VLA | 92 |\n"), null)
        val dialog = ShadowDialog.getLatestDialog(); val root = dialog.window!!.decorView
        await { descendants(root).filterIsInstance<TextView>().any { it.text.contains("点击") } }
        draw("reviewer-markdown", root)
        val beforeSize = descendants(root).filterIsInstance<TextView>().first { it.text.contains("点击") }.textSize
        descendants(root).first { it.contentDescription == "增大文字" }.performClick()
        assertTrue(descendants(root).filterIsInstance<TextView>().first { it.text.contains("点击") }.textSize > beforeSize)
        descendants(root).first { it.contentDescription == "切换 Markdown 排版与源码" }.performClick()
        assertTrue(descendants(root).filterIsInstance<TextView>().any { it.text.startsWith("# 实验报告") })
        descendants(root).first { it.contentDescription == "切换 Markdown 排版与源码" }.performClick()
        assertEquals(1, requests.size)
        val text = descendants(root).filterIsInstance<TextView>().first { it.text.contains("点击") }
        val span = (text.text as Spanned).getSpans(0, text.text.length, ClickableSpan::class.java).single(); span.onClick(text)
        assertEquals("docs/appendix.md", requests.last())
        reviewer.open(ReviewReference("docs/new.md"))
        pending.getValue("docs/appendix.md")(JSONObject().put("kind", "text").put("text", "STALE DOCUMENT"), null)
        pending.getValue("docs/new.md")(JSONObject().put("kind", "text").put("text", "# Current document"), null)
        await { descendants(root).filterIsInstance<TextView>().any { it.text.contains("Current document") } }
        assertFalse(descendants(root).filterIsInstance<TextView>().any { it.text.contains("STALE DOCUMENT") })
        reviewer.close(); controller.pause().stop().destroy()
    }
    @Test fun replacedAgentClosesOutstandingReviewerAndMalformedPdfShowsError() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        var valid = true; var done: ((JSONObject?, String?) -> Unit)? = null
        val reviewer = DocumentReviewer(activity, "/work/project", { valid }) { _, callback -> done = callback }
        reviewer.open(ReviewReference("paper.pdf")); val dialog = ShadowDialog.getLatestDialog()
        valid = false; done!!(JSONObject().put("kind", "pdf").put("data", "JVBERi0="), null)
        assertFalse(dialog.isShowing)
        val pdf = PdfPreview(activity, "not a pdf".toByteArray(), openPages = { throw java.io.IOException("Corrupt PDF") }); activity.setContentView(pdf)
        await { descendants(pdf).filterIsInstance<TextView>().any { it.text.contains("无法打开") } }
        pdf.close(); pdf.close(); controller.pause().stop().destroy()
    }
    @Test fun pdfErrorDeliveryDoesNotDependOnViewAttachment() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val pdf = PdfPreview(activity, "fixture".toByteArray(), openPages = { throw java.io.IOException("Corrupt fixture") })
        try {
            assertFalse(pdf.isAttachedToWindow)
            await { descendants(pdf).filterIsInstance<TextView>().any { it.text.contains("无法打开") } }
        } finally { pdf.close(); controller.pause().stop().destroy() }
    }

    @Test fun pdfRendersOnlyCurrentPageAndSupportsPageNavigationAndZoom() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
        val bytes = javaClass.classLoader!!.getResourceAsStream("reviewer-two-pages.pdf")!!.use { it.readBytes() }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        val requestedPages = mutableListOf<Int>()
        val view = PdfPreview(activity, bytes, 2, openPages = {
            object : PdfPages {
                override val count = 2
                override fun render(index: Int, desiredWidth: Int): Bitmap {
                    synchronized(requestedPages) { requestedPages += index }
                    val source = javaClass.classLoader!!.getResourceAsStream("reviewer-pdf-${index + 1}.png")!!.use { android.graphics.BitmapFactory.decodeStream(it)!! }
                    val (w, h) = ReviewDecoding.rasterSize(source.width, source.height, desiredWidth)
                    return Bitmap.createScaledBitmap(source, w, h, true).also { if (it !== source) source.recycle() }
                }
                override fun close() { closed.set(true) }
            }
        }); activity.setContentView(view)
        await { descendants(view).filterIsInstance<TextView>().any { it.text == "2 / 2" } }
        draw("reviewer-pdf-page-2", view)
        descendants(view).first { it.contentDescription == "上一页" }.performClick()
        await { descendants(view).filterIsInstance<TextView>().any { it.text == "1 / 2" } }
        assertEquals(1, descendants(view).filterIsInstance<ZoomImageView>().size)
        descendants(view).first { it.contentDescription == "放大 PDF" }.performClick(); assertTrue(view.picture.viewport.zoom > 1f)
        view.picture.changeZoom(3f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        await { descendants(view).filterIsInstance<TextView>().any { it.text == "1 / 2" } }
        assertEquals(3f, view.picture.viewport.zoom, .001f)
        draw("reviewer-pdf-zoom", view)
        view.close(); await { closed.get() }; assertTrue(requestedPages.all { it in 0..1 }); controller.pause().stop().destroy()
    }
}
