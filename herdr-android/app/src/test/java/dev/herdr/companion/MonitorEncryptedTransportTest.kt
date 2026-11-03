package dev.herdr.companion

import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import android.os.Looper
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
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonitorEncryptedTransportTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        context.getSharedPreferences("private_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("background-monitor", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)); if (condition()) return; Thread.sleep(10)
        }
        fail("encrypted monitor condition timed out")
    }
    @Test fun actualTCPMonitoringSurvivesUIBackgroundCompletionMuteAndReconnectWithoutDuplicate() {
        MockTcpMonitorRelay().use { fixture ->
            val shared = SharedRelay.get(context)
            shared.vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(shared.vault, lazyOf(SecretKeySpec(ByteArray(32) { 12 }, "AES")))
            shared.engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(shared.engine, fixture.state())
            val ui = Any(); shared.attachUI(ui) {}
            val preferences = MonitorPreferences(context); preferences.enabled = true
            val controller = Robolectric.buildService(AgentMonitorService::class.java).create(); val service = controller.get()
            val manager = context.getSystemService(NotificationManager::class.java)
            fun completions() = shadowOf(manager).allNotifications.filter { it.channelId == MonitorNotifications.COMPLETION_CHANNEL }
            try {
                service.onStartCommand(Intent(context, AgentMonitorService::class.java), 0, 1)
                waitUntil { fixture.requests.containsAll(listOf("a", "b")) && JSONObject(preferences.ledger()).length() == 2 }
                assertTrue(completions().isEmpty()); assertEquals(1, fixture.connected.get())
                shared.detachUI(ui); assertFalse(shared.uiVisible); assertTrue(shared.monitorOwned)
                val b = MonitorIdentity.key("fixture", fixture.agent("b")); preferences.mute(b, true)
                service.onStartCommand(Intent(context, AgentMonitorService::class.java).setAction(AgentMonitorService.POLICY_CHANGED).putExtra("key", b), 0, 2)
                fixture.states["a"] = "done"; fixture.states["b"] = "done"
                waitUntil { completions().size == 1 }
                assertTrue(completions().single().extras.getCharSequence("android.title").toString().contains("writer-a"))
                manager.cancel("completed:${MonitorIdentity.key("fixture", fixture.agent("a"))}", 1)
                fixture.disconnect()
                waitUntil { fixture.connected.get() >= 2 && shared.ready("fixture") }
                val count = fixture.requests.count { it == "a" }; waitUntil { fixture.requests.count { it == "a" } > count }
                assertTrue("reconnect notified an old completion", completions().isEmpty())
                // New complete turn between polls is detected even without observing its running phase.
                fixture.turn["a"] = "turn-a-next"
                waitUntil { completions().size == 1 }
                assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
                service.onStartCommand(Intent(context, AgentMonitorService::class.java).setAction(AgentMonitorService.STOP), 0, 3)
                assertFalse(preferences.enabled); assertFalse(shared.monitorOwned)
            } finally { controller.destroy(); shared.detachUI(ui) }
        }
    }
}
