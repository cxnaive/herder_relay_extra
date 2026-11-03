package dev.herdr.companion

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],qualifiers="w400dp-h850dp-xhdpi")
class TerminalViewportTest {
    private fun rows(from:Int,to:Int)=(from..to).joinToString("\n") {"output row $it"}
    private fun layout(view:View) {repeat(3){shadowOf(Looper.getMainLooper()).idle();view.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));view.layout(0,0,800,800)}}
    private fun drag(view:View,from:Float,to:Float,cancel:Boolean=false) {
        val start=SystemClock.uptimeMillis()
        for(i in 0..8) {
            val e=MotionEvent.obtain(start,start+i*35L,when(i){0->MotionEvent.ACTION_DOWN;8->if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE},400f,from+(to-from)*i/8,0)
            view.dispatchTouchEvent(e);e.recycle()
        }
    }
    @Test fun refreshQueuedBeforeDragCannotPullReaderBackToTheBottom() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible();val context=controller.get()
        val view=TerminalViewport(context,NativeTerminalView(context));context.setContentView(FrameLayout(context).apply { addView(view,FrameLayout.LayoutParams(800,800)) })
        try {
            view.update(rows(1,500),80);layout(view);assertTrue(view.scrollY>0)
            view.update(rows(1,510),80)
            drag(view,120f,650f,true)
            val reading=view.scrollY;assertFalse(view.followsOutput)
            layout(view);assertEquals(reading,view.scrollY)
            view.update(rows(1,530),80);layout(view);assertEquals(reading,view.scrollY)
            view.jumpToBottom();layout(view);assertTrue(view.followsOutput);assertFalse(view.canScrollVertically(1))
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun prependingHistoryKeepsTheReadLocationAndTopGestureCanRequestMore() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup().visible();val context=controller.get()
        val view=TerminalViewport(context,NativeTerminalView(context));context.setContentView(FrameLayout(context).apply { addView(view,FrameLayout.LayoutParams(800,800)) });var more=0;view.onReachHistoryTop={more++}
        try {
            view.update(rows(1001,1500),80);layout(view);view.restorePosition(300,false);layout(view)
            val beforeHeight=view.output.height
            view.update(rows(1,1500),80,true);layout(view)
            assertEquals(300+view.output.height-beforeHeight,view.scrollY)
            assertTrue(view.output.plainText().contains("output row 1"))
            view.restorePosition(0,false);layout(view);drag(view,200f,600f);assertEquals(1,more)
        }finally{controller.pause().stop().destroy()}
    }
}
