package dev.herdr.companion

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h850dp-xhdpi")
class SessionBindingDiagnosticsUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()

    @Test fun readerCanFetchRichDiagnosticsWithoutSelectingOrReadingHistory() {
        MockTcpMonitorRelay().use { fixture ->
            fixture.extraCapabilities += SessionBindingDiagnostics.CAPABILITY
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }
                .set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 26 }, "AES")))
            shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val reads = CopyOnWriteArrayList<JSONObject>()
            fixture.foregroundCommand = { command ->
                when (command.getString("type")) {
                    SessionBindingDiagnostics.ACTION -> {
                        reads += JSONObject(command.toString())
                        JSONObject().put("schema_version", 1).put("available", true).put("observation", "inventory_snapshot")
                            .put("target", command.getJSONObject("target"))
                            .put("binding", JSONObject().put("state", "ambiguous"))
                            .put("details", JSONObject().put("future_evidence", JSONArray().put(JSONObject().put("code", "two_roots").put("candidate_ids", JSONArray().put("root-a").put("root-b")))))
                    }
                    "unwatch_pane", "unwatch_conversation", "release_pane_size" -> null
                    else -> error("diagnostic UI attempted history, candidate selection or a write: ${command.optString("type")}")
                }
            }
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val activity = controller.get(); val root = FrameLayout(activity); activity.setContentView(root)
            val ui = NativeAgents(activity, shared.engine, shared.vault, { shared.agents }, { _, _ -> }, {}, {})
            fun waitUntil(condition: () -> Boolean) {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (System.nanoTime() < end) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
                    if (condition()) return
                    Thread.sleep(5)
                }
                fail("diagnostic UI timed out; reads=${reads.size}, failures=${fixture.failures}")
            }
            try {
                ui.attach(root); shared.attachUI(ui, ui::onEvent); waitUntil { shared.ready("fixture") }
                val agent = shared.agents.getValue("fixture").getJSONObject(0)
                agent.put("binding_state", "ambiguous").put("binding_reason", "runtime_ambiguous_no_report")
                ui.open("fixture", agent)
                ui.javaClass.getDeclaredMethod("conversationOptions").apply { isAccessible = true }.invoke(ui)
                val menu = ShadowAlertDialog.getLatestAlertDialog()
                val index = (0 until menu.listView.adapter.count).single { menu.listView.adapter.getItem(it) == "会话绑定诊断" }
                shadowOf(menu).clickOnItem(index)
                waitUntil {
                    val dialog = ShadowAlertDialog.getLatestAlertDialog()
                    dialog != menu && views(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("future_evidence") }
                }
                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                val text = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                assertTrue(text.contains("root-a")); assertTrue(text.contains("root-b")); assertTrue(text.contains("仅供查看"))
                assertEquals(1, reads.size); assertEquals("get_session_binding_diagnostics", reads.single().optString("type"))
                assertFalse(PrimarySessionBinding.writable(agent)); assertEquals("session-a", agent.optString("agent_session_id"))
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            } finally { ui.destroy(); shared.detachUI(ui); controller.pause().stop().destroy() }
        }
    }
}
