package dev.herdr.companion

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,35], qualifiers = "w400dp-h850dp-xhdpi")
class TerminalScrollRegressionTest {
    @Test fun nestedTerminalRetainsFullHistoryHeightAndAcceptsVerticalTouch() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible(); val activity=controller.get()
        activity.getSharedPreferences("terminal-appearance",0).edit().clear().commit()
        val output=NativeTerminalView(activity)
        val scroll=ScrollView(activity).apply {
            isFillViewport=true
            addView(HorizontalScrollView(activity).apply { isFillViewport=true;addView(output) })
            addOnLayoutChangeListener { _,l,_,r,_,_,_,_,_ -> output.setViewportWidth(r-l) }
        }
        activity.setContentView(scroll)
        output.show((1..1000).joinToString("\n") { "line $it completed" },80)
        fun layout() {
            repeat(3) { shadowOf(Looper.getMainLooper()).idle();scroll.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(900,View.MeasureSpec.EXACTLY));scroll.layout(0,0,800,900) }
        }
        try {
            layout()
            assertTrue("outputHeight=${output.height} viewport=${scroll.height} rows=${output.displayedRows}",output.height>900*3)
            scroll.scrollTo(0, output.height-scroll.height)
            val before=scroll.scrollY;assertTrue(before>0)
            val start=SystemClock.uptimeMillis()
            for(i in 0..8) {
                val action=if(i==0) MotionEvent.ACTION_DOWN else if(i==8) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                val e=MotionEvent.obtain(start,start+i*25L,action,400f,200f+i*60f,0)
                scroll.dispatchTouchEvent(e);e.recycle()
            }
            assertTrue("before=$before after=${scroll.scrollY}",scroll.scrollY<before-100)
            scroll.scrollTo(0,0);assertTrue(scroll.canScrollVertically(1))
            assertTrue(output.plainText().contains("line 1 completed"))
        } finally {controller.pause().stop().destroy()}
    }
}
