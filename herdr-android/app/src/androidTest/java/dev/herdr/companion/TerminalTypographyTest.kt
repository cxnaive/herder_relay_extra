package dev.herdr.companion

import android.view.View
import android.util.TypedValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a dedicated test device, not an installation with personal relay credentials. */
@RunWith(AndroidJUnit4::class)
class TerminalTypographyTest {
    @Test fun bundledFontIsNarrowWithoutGeometricStretchingAndHistoryCanExceedViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val view = NativeTerminalView(context)
            val ratio = view.cellWidth / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, view.fontSizeSp, context.resources.displayMetrics)
            assertTrue("Expected half-em terminal cells, got $ratio", ratio in 0.47f..0.53f)
            view.setViewportWidth(720)
            view.show(List(1500) { "历史 $it · 中文 · é" }.joinToString("\n"))
            view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue(view.measuredHeight > 1500 * view.lineHeight)
            assertTrue(view.measuredWidth <= 720)
            val oldCell = view.cellWidth
            view.fontSize(1)
            assertTrue(view.cellWidth > oldCell)
            view.fontSize(-1)
        }
    }
}
