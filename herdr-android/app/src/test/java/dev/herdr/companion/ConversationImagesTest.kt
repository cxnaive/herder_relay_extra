package dev.herdr.companion

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationImagesTest {
    private lateinit var controller: org.robolectric.android.controller.ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var images: ConversationImages
    private val callbacks = mutableListOf<(String?, String?) -> Unit>()
    @Before fun setup() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        root = FrameLayout(controller.get()); controller.get().setContentView(root)
        images = ConversationImages(controller.get())
    }
    @After fun cleanup() { images.close(); controller.pause().stop().destroy() }
    private fun card(key: String = "selected:entry:plot") = images.view(ConversationPresentation.Image("plots/plot.png", "plot.png", "plots/plot.png"), key, {}) { _, done -> callbacks += done }
    private fun layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 800, 1000)
        root.viewTreeObserver.dispatchOnGlobalLayout()
    }
    private fun attach(card: View) {
        root.addView(card, FrameLayout.LayoutParams(-1, -2)); layout()
        root.viewTreeObserver.javaClass.getDeclaredMethod("dispatchOnScrollChanged").apply { isAccessible = true }.invoke(root.viewTreeObserver); shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun failedVisiblePreviewRetriesInsteadOfBeingPermanentlyCachedAsFailure() {
        attach(card()); assertEquals(1, callbacks.size)
        callbacks.first()(null, "连接中断，请重试读取")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("Transient failure should retry while visible", 2, callbacks.size)
    }
    @Test fun historyRerenderSharesThePendingImageRead() {
        attach(card()); assertEquals(1, callbacks.size)
        root.removeAllViews(); attach(card())
        assertEquals("Rebuilding history must not duplicate an in-flight read", 1, callbacks.size)
    }
    @Test fun cardLoadsAfterLayoutWithoutRequiringTheUserToScroll() {
        val card = card(); root.addView(card, FrameLayout.LayoutParams(-1, -2))
        // The initial attach callback can run before measurement and therefore see no visible rect.
        shadowOf(Looper.getMainLooper()).idle()
        layout(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Image became visible after layout but was skipped", 1, callbacks.size)
    }
    private fun text(card: View) = ((card as LinearLayout).getChildAt(1) as TextView).text.toString()
    private fun picture(card: View) = (card as LinearLayout).getChildAt(0) as ImageView
    private fun png(color: Int = Color.GREEN): String {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
    }
    private fun awaitImage(card: View) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (picture(card).drawable is BitmapDrawable) return
            Thread.sleep(5)
        }
        fail("Image decode did not reach the visible card: ${text(card)}")
    }
    @Test fun openingCachedHistoryOfflineWaitsThenLoadsWithoutNewMessagesOrScrolling() {
        images.setAvailability(true, false)
        val view = card(); attach(view)
        assertEquals(0, callbacks.size); assertTrue(text(view).contains("等待连接"))
        images.setAvailability(true, true); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, callbacks.size)
        callbacks.last()(png(), null); awaitImage(view)
        assertTrue(text(view).contains("全屏查看"))
    }
    @Test fun reconnectDiscardsOldReadFailureAndAutomaticallyLoadsTheSameVisibleCard() {
        val view = card(); attach(view); val old = callbacks.single()
        images.setAvailability(true, false); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(text(view).contains("等待连接"))
        images.setAvailability(true, true); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, callbacks.size)
        old(null, "old socket failed"); callbacks.last()(png(), null); awaitImage(view)
        assertTrue(text(view).contains("全屏查看"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)); assertEquals(2, callbacks.size)
    }
    @Test fun backgroundPauseDoesNotFetchOrApplyOldRepliesAndResumeRetries() {
        val view = card(); attach(view); val old = callbacks.single()
        images.setAvailability(false, true)
        old(png(Color.RED), null); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNull(picture(view).drawable); assertEquals(1, callbacks.size)
        images.setAvailability(true, true); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, callbacks.size)
        callbacks.last()(png(), null); awaitImage(view)
        assertEquals(Color.GREEN, (picture(view).drawable as BitmapDrawable).bitmap.getPixel(0, 0))
    }
    @Test fun onlyTwoVisibleFilesAreInFlightAndQueuedCardsStartAsSlotsFree() {
        val first = card("one"); val second = card("two"); val third = card("three")
        attach(first); attach(second); attach(third)
        assertEquals(2, callbacks.size); assertTrue(text(third).contains("等待加载"))
        callbacks.first()(png(), null); awaitImage(first)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, callbacks.size)
    }
    @Test fun retriesAreBoundedAndManualTapCanRetryAnExhaustedFailure() {
        val view = card(); attach(view)
        repeat(3) { callbacks.last()(null, "file temporarily unavailable"); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4)) }
        assertEquals(3, callbacks.size); assertTrue(text(view).contains("点按重试"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1)); assertEquals(3, callbacks.size)
        (view as LinearLayout).getChildAt(1).performClick(); shadowOf(Looper.getMainLooper()).idle(); assertEquals(4, callbacks.size)
        callbacks.last()(png(), null); awaitImage(view)
    }
    @Test fun lateSuccessAfterChangingSessionCannotFillTheNewSessionsCache() {
        attach(card()); val old = callbacks.single()
        images.clear(); root.removeAllViews(); val next = card(); attach(next)
        old(png(Color.RED), null); shadowOf(Looper.getMainLooper()).idle()
        assertNull(picture(next).drawable)
        callbacks.last()(png(), null); awaitImage(next)
        assertEquals(Color.GREEN, (picture(next).drawable as BitmapDrawable).bitmap.getPixel(0, 0))
    }
    @Test fun cacheSurvivesDisconnectAndHistoryRerenderWithoutFetchingAgain() {
        val first = card(); attach(first); callbacks.single()(png(), null); awaitImage(first)
        root.removeAllViews(); images.setAvailability(true, false)
        val second = card(); attach(second); awaitImage(second)
        assertEquals(1, callbacks.size)
    }
    @Test fun unmountedFailedCardDoesNotRetryUntilItIsVisibleAgain() {
        attach(card()); callbacks.single()(null, "timeout"); root.removeAllViews()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)); assertEquals(1, callbacks.size)
        val next = card(); attach(next); assertEquals(2, callbacks.size)
        callbacks.last()(png(), null); awaitImage(next)
    }
    @Test fun embeddedImageCanDecodeOfflineAndNeverCallsTheFileEndpoint() {
        images.setAvailability(true, false)
        val view = images.view(ConversationPresentation.Image(png(), "embedded", null), "inline", {}) { _, _ -> fail("Embedded image fetched remotely") }
        attach(view); awaitImage(view)
    }
    @Test fun invalidBytesDoNotLoopAndOutOfWorkspaceImagesNeverFetch() {
        val view = card(); attach(view); callbacks.single()("not image bytes", null)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!text(view).contains("点按重试") && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(text(view).contains("点按重试"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1)); assertEquals(1, callbacks.size)
        val outside = images.view(ConversationPresentation.Image("/private/plot.png", "plot.png", null), "outside", {}) { _, _ -> fail("Out of workspace fetch") }
        root.removeAllViews(); attach(outside); outside.performClick()
        assertTrue(text(outside).contains("非当前工作区"))
    }
    @Test fun pendingReadCompletesIntoTheNewCardAfterHistoryRerender() {
        attach(card()); val done = callbacks.single()
        root.removeAllViews(); val next = card(); attach(next)
        done(png(), null); awaitImage(next)
        assertEquals(1, callbacks.size)
    }
    @Test fun previewFailureStillAllowsOpeningTheFullResolutionReviewer() {
        var opened = 0
        val view = images.view(ConversationPresentation.Image("large.png", "large.png", "large.png"), "large", { opened++ }) { _, done -> callbacks += done }
        attach(view); callbacks.single()(null, "preview too large")
        picture(view).performClick(); assertEquals(1, opened)
        assertEquals(1, callbacks.size)
    }
}
