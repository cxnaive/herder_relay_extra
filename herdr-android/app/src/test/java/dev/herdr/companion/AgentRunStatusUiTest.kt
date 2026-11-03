package dev.herdr.companion

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentRunStatusUiTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun draw(name: String, view: View, width: Int = 800) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.AT_MOST)); view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff0b1218.toInt()); view.draw(Canvas(bitmap))
        val out = File("../screenshots/qa-0.2.10"); out.mkdirs(); File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun statesHaveDistinctTextIndicatorsAndFrozenFinishedTime() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup(); val context = activity.get()
        val all = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20) }
        val agent = JSONObject().put("status", "working")
        var clicks = 0
        val samples = listOf("working", "blocked", "done", "failed", "interrupted", "idle")
        for (status in samples) {
            val p = AgentRunPresentation(); p.accept(AgentRunPresentationTest.sample(status, 749000, status in listOf("working", "blocked")).put("attention_kind", "approval"), 1000)
            val display = p.display(agent, true, true, true, 1000)
            val view = AgentRunStatusView(context) { clicks++ }; view.show(display)
            all.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })
            assertEquals(status == "working", views(view).filterIsInstance<ProgressBar>().single().visibility == View.VISIBLE)
            assertTrue(view.contentDescription.toString().contains(display.headline)); view.performClick()
        }
        val offline = AgentRunPresentation().apply { accept(AgentRunPresentationTest.sample("working", 749000, true), 1000) }
        all.addView(AgentRunStatusView(context) {}.apply { show(offline.display(agent, false, true, true, 1000)) })
        draw("run-status-states", all); assertEquals(samples.size, clicks); activity.pause().stop().destroy()
    }
    @Test fun realConversationShowsStickyStatusSeparateFromHistoryAndUpdatesWithoutRebuilding() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup(); val context = activity.get()
        context.getSharedPreferences("private_state", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val vault = Vault(context)
        // JVM graphics fixture supplies an ephemeral AES key; production still uses AndroidKeyStore.
        vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(vault, lazyOf(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")))
        val engine = RelayEngine(context, vault) {}
        val agent = JSONObject().put("pane_id", "pane-1").put("terminal_id", "term-1").put("generation", 1).put("agent_session_id", "session-1").put("workspace_id", "ws-1")
            .put("cwd", "/work/paper").put("agent", "codex").put("name", "Codex").put("tab_label", "writer-1").put("status", "working")
        val rows = JSONArray().put(agent)
        val ui = NativeAgents(context, engine, vault, { mapOf("computer" to rows) }, { _, _ -> }, {}, {})
        val host = FrameLayout(context); context.setContentView(host); ui.attach(host)
        ui.onEvent(JSONObject().put("type", "message").put("relayId", "computer").put("message", JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready"))))
        ui.onEvent(JSONObject().put("type", "status").put("relayId", "computer").put("status", "connected"))
        ui.open("computer", agent); shadowOf(Looper.getMainLooper()).idle()
        val presentation = ui.javaClass.getDeclaredField("runPresentation").apply { isAccessible = true }.get(ui) as AgentRunPresentation
        presentation.accept(AgentRunPresentationTest.sample("working", 123000, true), SystemClock.elapsedRealtime())
        ui.javaClass.getDeclaredMethod("updateRunStatus").apply { isAccessible = true }.invoke(ui)
        val status = views(host).filterIsInstance<AgentRunStatusView>().single()
        assertEquals("本轮 02:03", views(status).filterIsInstance<TextView>().first { it.text.startsWith("本轮") }.text.toString())
        assertTrue(status.parent is LinearLayout)
        // The fixed status bar is outside the conversation ScrollView; ticking must retain its view.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertSame(status, views(host).filterIsInstance<AgentRunStatusView>().single())
        val updated = views(status).filterIsInstance<TextView>().first { it.text.startsWith("本轮") }.text.toString()
        assertTrue("timer did not advance: $updated", updated in listOf("本轮 02:04", "本轮 02:05"))
        draw("conversation-running-history-unavailable", host)
        ui.onEvent(JSONObject().put("type", "status").put("relayId", "computer").put("status", "closed"))
        assertTrue(status.contentDescription.toString().startsWith("连接中断")); assertFalse(views(status).filterIsInstance<ProgressBar>().single().visibility == View.VISIBLE)
        val frozen = status.contentDescription.toString(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20)); assertEquals(frozen, status.contentDescription.toString())
        ui.destroy(); engine.destroy(); activity.pause().stop().destroy()
    }
    @Test fun lateStatusRepliesCannotCrossAgentsOrResurrectAfterPause() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup(); val context = activity.get()
        context.getSharedPreferences("private_state", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val vault = Vault(context)
        vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(vault, lazyOf(javax.crypto.spec.SecretKeySpec(ByteArray(32) { 8 }, "AES")))
        val engine = RelayEngine(context, vault) {}
        fun agent(id: String) = JSONObject().put("pane_id", id).put("terminal_id", "term-$id").put("agent_session_id", "session-$id").put("generation", 1)
            .put("workspace_id", "ws-1").put("cwd", "/work/paper").put("agent", "codex").put("tab_label", id).put("status", "working")
        val a = agent("a"); val b = agent("b")
        val ui = NativeAgents(context, engine, vault, { mapOf("computer" to JSONArray().put(a).put(b)) }, { _, _ -> }, {}, {})
        val host = FrameLayout(context); context.setContentView(host); ui.attach(host)
        ui.onEvent(JSONObject().put("type", "message").put("relayId", "computer").put("message", JSONObject().put("type", "push_config").put("protocol", 3).put("inventory", JSONObject().put("state", "ready"))
            .put("capabilities", JSONArray().put("agent_run_state_v1"))))
        ui.onEvent(JSONObject().put("type", "status").put("relayId", "computer").put("status", "connected"))
        @Suppress("UNCHECKED_CAST")
        fun pending() = engine.javaClass.getDeclaredField("pending").apply { isAccessible = true }.get(engine) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        fun callback(value: Any) = value.javaClass.getDeclaredField("callback").apply { isAccessible = true }.get(value) as (JSONObject?, String?) -> Unit
        ui.open("computer", a)
        val firstRequests = pending().toMap(); val lateA = callback(firstRequests.values.first())
        val read = ui.javaClass.getDeclaredMethod("readRunStatus").apply { isAccessible = true }
        read.invoke(ui); read.invoke(ui); assertEquals("an in-flight status read was duplicated", firstRequests.size, pending().size)
        ui.open("computer", b)
        val replyB = callback(pending().filterKeys { it !in firstRequests }.values.first())
        replyB(AgentRunPresentationTest.sample("working", 5000, true), null)
        lateA(AgentRunPresentationTest.sample("working", 990000, true), null)
        val view = views(host).filterIsInstance<AgentRunStatusView>().single()
        assertTrue(view.contentDescription.toString().contains("本轮 00:05")); assertFalse(view.contentDescription.toString().contains("16:30"))
        ui.onEvent(JSONObject().put("type", "status").put("relayId", "computer").put("status", "closed"))
        replyB(AgentRunPresentationTest.sample("working", 10000, true), null)
        assertTrue(view.contentDescription.toString().startsWith("连接中断"))
        ui.pause(); val frozen = view.contentDescription.toString()
        lateA(AgentRunPresentationTest.sample("working", 1110000, true), null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2)); assertEquals(frozen, view.contentDescription.toString())
        ui.destroy(); engine.destroy(); activity.pause().stop().destroy()
    }
    @Test fun timerDoesNotOverflowNarrowPhone() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup(); val context = activity.get()
        val view = AgentRunStatusView(context) {}
        view.show(AgentRunPresentation.Display("等待审批", "本轮 123:45:56", "本轮总用时，包含工具执行和等待时间", "waiting"))
        draw("long-turn-narrow", view, 640)
        val labels = views(view).filterIsInstance<TextView>()
        assertTrue(labels.all { it.right <= (it.parent as View).width })
        activity.pause().stop().destroy()
    }
}
