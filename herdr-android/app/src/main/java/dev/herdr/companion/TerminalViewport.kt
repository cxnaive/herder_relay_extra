package dev.herdr.companion

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.abs

/** A reader's scroll position wins over asynchronous snapshots and their later layout pass. */
internal class TerminalViewport(context: Context, val output: NativeTerminalView) : ScrollView(context) {
    var followsOutput = true; private set
    var onReadingHistory: (Boolean) -> Unit = {}
    var onReachHistoryTop: () -> Unit = {}
    private data class Anchor(val y: Int, val height: Int, val older: Boolean)
    private var anchor: Anchor? = null
    private var userGesture = false
    private var dragged = false
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    init {
        isFillViewport = true; isVerticalScrollBarEnabled = true
        contentDescription = "终端输出，上下滑动查看已加载历史；原始列宽时可左右滑动"
        addView(object : HorizontalScrollView(context) {
            private var x = 0f; private var y = 0f; private var vertical = false
            init { isFillViewport = true; addView(output) }
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { x = event.x; y = event.y; vertical = false }
                if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.y-y)>slop && abs(event.y-y)>abs(event.x-x)) vertical = true
                return if (vertical || output.wrapToPhone) false else super.onInterceptTouchEvent(event)
            }
        })
        addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ -> output.setViewportWidth(r-l) }
    }
    private fun follow(value: Boolean) { if (followsOutput != value) { followsOutput = value; onReadingHistory(!value) } }
    fun update(content: String, columns: Int, older: Boolean = false) {
        // Keep the first pre-layout anchor if several frames arrive in one UI frame.
        if (anchor == null) anchor = Anchor(scrollY, output.height, older)
        else if (older) anchor = anchor?.copy(older = true)
        if (older) follow(false)
        output.show(content, columns); requestLayout()
    }
    fun jumpToBottom() { follow(true); anchor = null; requestLayout() }
    fun restorePosition(y: Int, following: Boolean) { follow(following); anchor = Anchor(y, output.height, false); requestLayout() }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pending = anchor
        val savedY = pending?.y ?: scrollY
        super.onLayout(changed,l,t,r,b)
        val position = TerminalLayout.scrollY(savedY, pending?.height ?: output.height, output.height, height, followsOutput && !dragged,
            pending?.older == true)
        scrollTo(scrollX, position); anchor = null
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { userGesture = true; dragged = false; downY = event.y }
            MotionEvent.ACTION_MOVE -> if (abs(event.y-downY)>slop) {
                dragged = true; follow(false)
                // A frame queued before the finger moved must not put the view back afterwards.
                anchor = null
            }
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragged && event.actionMasked == MotionEvent.ACTION_UP) {
                if (!canScrollVertically(1)) follow(true)
                if (!canScrollVertically(-1)) onReachHistoryTop()
            }
            userGesture = false; dragged = false
        }
        return handled
    }
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l,t,oldl,oldt)
        if (userGesture && t != oldt) follow(false)
    }
}
