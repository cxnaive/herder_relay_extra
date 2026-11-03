package dev.herdr.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryLoadUiTest {
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    @Test fun loadImmediatelyShowsSpinnerAndAllowsCancelWithProgressAndRetry() {
        var loaded = 0; var cancelled = 0
        lateinit var view: HistoryLoadView
        view = HistoryLoadView(RuntimeEnvironment.getApplication(), { loaded++; view.update(true, true, true) }, { cancelled++; view.update(false, true, true, "已取消等待，已读历史保留") })
        view.update(false, false, true)
        descendants(view).filterIsInstance<Button>().single().performClick()
        assertEquals(1, loaded); assertEquals(View.VISIBLE, descendants(view).filterIsInstance<ProgressBar>().single().visibility)
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.contains("正在读取更早消息") })
        view.update(true, true, true, progress = JSONObject().put("scanned_bytes", 1048576).put("source_bytes", 4194304))
        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.contains("1.0 MB / 4.0 MB") })
        val w = 800; view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); view.layout(0, 0, w, view.measuredHeight)
        val b = Bitmap.createBitmap(w, view.measuredHeight, Bitmap.Config.ARGB_8888); val canvas = Canvas(b); canvas.drawColor(0xff101b22.toInt()); view.draw(canvas)
        val dir = File("build/reports/ui-027").apply { mkdirs() }; File(dir, "history-progress.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; b.recycle()
        descendants(view).filterIsInstance<Button>().single().performClick(); assertEquals(1, cancelled)
        assertEquals(View.GONE, descendants(view).filterIsInstance<ProgressBar>().single().visibility)
        assertEquals("重试", descendants(view).filterIsInstance<Button>().single().text)
        view.update(false, false, false); assertEquals(View.GONE, view.visibility)
    }
    @Test fun interruptedReadsDoNotClaimAnUnknownWriteOutcome() {
        val context = RuntimeEnvironment.getApplication()
        val engine = RelayEngine(context, Vault(context)) {}
        var readError = ""; var writeError = ""
        try {
            engine.request("fixture", JSONObject().put("type", "get_conversation_history")) { _, error -> readError = error.orEmpty() }
            engine.request("fixture", JSONObject().put("type", "submit_prompt")) { _, error -> writeError = error.orEmpty() }
            engine.javaClass.getDeclaredMethod("failPending", String::class.java, String::class.java).apply { isAccessible = true }
                .invoke(engine, "fixture", "连接中断，执行结果未确认；请核对后再操作")
            assertTrue(readError.contains("已读内容保留")); assertFalse(readError.contains("执行结果未确认"))
            assertTrue(writeError.contains("执行结果未确认"))
        } finally { engine.destroy() }
    }

}
