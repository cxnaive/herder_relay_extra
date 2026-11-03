package dev.herdr.companion

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a real Android runtime: host-side Robolectric has no PdfRenderer JNI. */
@RunWith(AndroidJUnit4::class)
class NativePdfReviewerTest {
    @Test fun androidPdfRendererDrawsPagesAtHigherResolutionAndCleansPrivateCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        val bytes = instrumentation.context.assets.open("reviewer-two-pages.pdf").use { it.readBytes() }
        AndroidPdfPages(context, bytes).use { pages ->
            assertEquals(2, pages.count)
            val normal = pages.render(0, 800)
            val sharp = pages.render(1, 2400)
            assertTrue(sharp.width > normal.width)
            assertEquals(595.0 / 842, sharp.width.toDouble() / sharp.height, .003)
            assertTrue((0 until normal.width step 10).any { x -> (0 until normal.height step 10).any { y -> normal.getPixel(x, y) != Color.WHITE } })
            normal.recycle(); sharp.recycle()
        }
        val after = context.cacheDir.listFiles().orEmpty().map { it.name }.toSet()
        assertTrue((after - before).none { it.startsWith("review-") && it.endsWith(".pdf") })
    }
}
