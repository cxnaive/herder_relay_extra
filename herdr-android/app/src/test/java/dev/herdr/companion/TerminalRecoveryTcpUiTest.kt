package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
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
@Config(sdk = [28])
class TerminalRecoveryTcpUiTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        for (name in listOf("private_state", "background-monitor")) context.getSharedPreferences(name, 0).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun get(owner: Any, field: String) = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(owner)
    private fun invoke(ui: NativeAgents, method: String) = ui.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(ui)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun controllerCanUseTerminalAndLifecycleWhileChatIsUnresolved() {
        MockTcpMonitorRelay().use { fixture ->
            fixture.role = "controller"
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 28 }, "AES")))
            shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val writes = CopyOnWriteArrayList<JSONObject>()
            val reads = CopyOnWriteArrayList<JSONObject>()
            fixture.foregroundEnvelope = { command ->
                fun result(data: JSONObject = JSONObject()) = JSONObject().put("type", "command_result").put("request_id", command.optString("request_id")).put("ok", true).put("phase", "completed").put("data", data)
                when (command.optString("type")) {
                    "read_pane" -> {
                        reads += command
                        JSONObject().put("type", "pane_content").put("request_id", command.optString("request_id"))
                            .put("pane_id", "a").put("target", command.getJSONObject("target")).put("content", "Synthetic terminal waiting for input").put("no_echo", false)
                    }
                    "send_terminal_text", "send_keys", "agent_stop", "agent_restart", "agent_clear" -> { writes += JSONObject(command.toString()); result() }
                    "unwatch_pane", "unwatch_conversation", "release_pane_size", "watch_pane", "pane_applied", "refresh_agents" -> null
                    else -> error("unexpected history/approval/write: ${command.optString("type")}")
                }
            }
            val controller = Robolectric.buildActivity(Activity::class.java).setup(); val activity = controller.get()
            val root = FrameLayout(activity); activity.setContentView(root)
            val ui = NativeAgents(activity, shared.engine, shared.vault, { shared.agents }, { _, _ -> }, {}, {})
            fun waitUntil(condition: () -> Boolean) {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
                while (System.nanoTime() < end) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
                    if (condition()) return
                    Thread.sleep(5)
                }
                fail("terminal recovery timeout, reads=${reads.size}, writes=${writes.size}, errors=${fixture.failures}")
            }
            try {
                ui.attach(root); shared.attachUI(ui, ui::onEvent); waitUntil { shared.ready("fixture") }
                @Suppress("UNCHECKED_CAST")
                val configs = get(ui, "configs") as MutableMap<String, JSONObject>
                configs.getValue("fixture").put("capabilities", JSONArray()) // No timing endpoint needed for a terminal-only fixture.
                val a = shared.agents.getValue("fixture").getJSONObject(0)
                a.put("binding_state", "ambiguous").put("binding_reason", "runtime_ambiguous_no_report").put("binding_epoch", 7).put("agent_session_id", "")
                ui.open("fixture", a)
                val conversationComposer = get(ui, "composerPanel") as NativeComposerView
                conversationComposer.editor.setText("do not submit as conversation")
                assertFalse(conversationComposer.sendButton.isEnabled)
                views(root).single { it.contentDescription == "终端" }.performClick()
                waitUntil { reads.isNotEmpty() && get(ui, "terminalReadPending") == false }
                val composer = get(ui, "composerPanel") as NativeComposerView
                composer.editor.setText("literal synthetic text")
                assertTrue(composer.sendButton.isEnabled); composer.sendButton.performClick()
                waitUntil { writes.size == 1 && composer.editor.text.isEmpty() }
                val keys = get(ui, "terminalKeys") as View
                waitUntil { views(keys).filterIsInstance<Button>().single { it.text == "Enter" }.isEnabled }
                views(keys).filterIsInstance<Button>().single { it.text == "Enter" }.performClick()
                waitUntil { writes.size == 2 && (get(ui, "pendingMutations") as Set<*>).isEmpty() }
                assertEquals("send_terminal_text", writes[0].getString("type")); assertEquals("literal synthetic text", writes[0].getString("text"))
                assertEquals("Enter", writes[1].getJSONArray("keys").getString(0))
                for (operation in listOf("agent_stop", "agent_restart", "agent_clear")) {
                    val before = writes.size
                    ui.javaClass.getDeclaredMethod("mutate", String::class.java, JSONObject::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(ui, operation, JSONObject(), false)
                    waitUntil { writes.size == before + 1 && (get(ui, "pendingMutations") as Set<*>).isEmpty() }
                    assertEquals(operation, writes.last().getString("type"))
                }
                for (command in writes) {
                    assertEquals("terminal-a", command.getJSONObject("target").getString("terminal_id"))
                    assertEquals("", command.getJSONObject("target").getString("agent_session_id"))
                    assertEquals(7L, command.getJSONObject("target").getLong("binding_epoch"))
                }
                // An earlier unconfirmed send still blocks keys and input.
                val key = "delivery:" + WorkspaceBinding.fromAgent("fixture", a).storageKey()
                shared.vault.write(key, "unconfirmed"); invoke(ui, "updateSendState")
                composer.editor.setText("must not send twice"); assertFalse(composer.sendButton.isEnabled)
                assertFalse(views(keys).filterIsInstance<Button>().single { it.text == "Enter" }.isEnabled)
                assertEquals(5, writes.size); assertFalse(PrimarySessionBinding.readable(a))
                shared.vault.remove(key)
                // A delayed management confirmation must not retarget another pane.
                invoke(ui, "manageAgent")
                val menu = ShadowAlertDialog.getLatestAlertDialog()
                shadowOf(menu).clickOnItem(1)
                val confirm = ShadowAlertDialog.getLatestAlertDialog()
                ui.open("fixture", JSONObject(a.toString()).put("terminal_id", "replaced-terminal"))
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
                assertEquals(5, writes.size)
                // Reader credentials still deny terminal and lifecycle mutations.
                val engineState = get(shared.engine, "state") as JSONObject
                engineState.getJSONObject("auth").getJSONObject("relays").getJSONObject("fixture").put("role", "reader")
                ui.open("fixture", a)
                views(root).single { it.contentDescription == "终端" }.performClick()
                val readerComposer = get(ui, "composerPanel") as NativeComposerView
                readerComposer.editor.setText("reader must not send")
                assertFalse(readerComposer.sendButton.isEnabled)
                ui.javaClass.getDeclaredMethod("mutate", String::class.java, JSONObject::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(ui, "agent_stop", JSONObject(), false)
                shadowOf(Looper.getMainLooper()).idle(); assertEquals(5, writes.size)
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            } finally { ui.destroy(); shared.detachUI(ui); controller.pause().stop().destroy() }
        }
    }
}
