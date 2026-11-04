package dev.herdr.companion

import android.content.Context
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ComputerNamingEngineTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val events = mutableListOf<JSONObject>()
    private lateinit var vault: Vault
    private lateinit var engine: RelayEngine
    private val key = SecretKeySpec(ByteArray(32) { 24 }, "AES")
    private fun installKey(target: Vault) = target.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(target, lazyOf(key))
    @Before fun setup() {
        context.getSharedPreferences("private_state", 0).edit().clear().commit()
        vault = Vault(context); installKey(vault)
        engine = RelayEngine(context, vault) { events += it }
        shadowOf(Looper.getMainLooper()).idle(); events.clear()
    }
    @After fun cleanup() { engine.destroy(); shadowOf(Looper.getMainLooper()).idle() }
    private fun seed(state: JSONObject) {
        vault.write("state", state.toString())
        engine.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(engine, JSONObject(state.toString()))
    }
    private fun localState(): JSONObject {
        val rows = JSONArray(); val credentials = JSONObject()
        for (id in listOf("first", "second")) {
            rows.put(JSONObject().put("id", id).put("label", "同名电脑").put("gatewayUrl", "tcp://computer.invalid:8377")
                .put("gatewayRelayId", "routing-$id").put("rendezvousKey", "fixture-route-$id").put("paired", true))
            credentials.put(id, JSONObject().put("kind", "credential").put("role", "reader").put("secret", "fixture-secret-$id").put("deviceId", "device-$id"))
        }
        return JSONObject().put("relays", rows).put("auth", JSONObject().put("version", 1).put("relays", credentials))
    }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Synthetic computer naming operation timed out")
    }
    @Test fun offlineReaderCanRenameOnlyTheSelectedComputerAndReloadItsName() {
        val before = localState(); seed(before)
        vault.write("prompt:fixture", "existing draft")
        engine.renameComputer("first", "  家里工作站  ")
        shadowOf(Looper.getMainLooper()).idle()
        val expected = JSONObject(before.toString()).apply { getJSONArray("relays").getJSONObject(0).put("label", "家里工作站") }
        assertEquals(expected.toString(), engine.snapshot().toString())
        assertEquals(expected.toString(), vault.read("state"))
        assertEquals("existing draft", vault.read("prompt:fixture"))
        assertEquals(listOf("persist", "renamed"), events.map { it.getString("type") })
        assertEquals("first", events.last().getString("relayId"))
        assertFalse(events.last().toString().contains("fixture-secret"))
        val reopenedVault = Vault(context); installKey(reopenedVault)
        val reopened = RelayEngine(context, reopenedVault) {}
        try { assertEquals(expected.toString(), reopened.snapshot().toString()) } finally { reopened.destroy() }
    }
    @Test fun invalidNameOrRemovedComputerDoesNotModifyStateOrEmitSuccess() {
        val before = localState(); seed(before)
        for ((id, name) in listOf("first" to "   ", "first" to "x".repeat(49), "first" to "a\nb", "removed" to "公司电脑")) {
            assertThrows(IllegalArgumentException::class.java) { engine.renameComputer(id, name) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before.toString(), engine.snapshot().toString())
        assertEquals(before.toString(), vault.read("state")); assertTrue(events.isEmpty())
    }
    @Test fun failedSaveLeavesBothMemoryAndEncryptedStorageUnchanged() {
        val before = localState(); seed(before)
        val saved = context.getSharedPreferences("private_state", 0).getString("state", null)
        vault.javaClass.getDeclaredField("key\$delegate").apply { isAccessible = true }.set(vault, lazy { error("Synthetic storage failure") })
        assertThrows(IllegalStateException::class.java) { engine.renameComputer("first", "新名字") }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before.toString(), engine.snapshot().toString())
        assertEquals(saved, context.getSharedPreferences("private_state", 0).getString("state", null))
        assertTrue(events.isEmpty())
    }
    @Test fun savingUnchangedNameDoesNotRewriteCredentialsOrEmitEvents() {
        seed(localState())
        val saved = context.getSharedPreferences("private_state", 0).getString("state", null)
        engine.renameComputer("first", " 同名电脑 ")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(saved, context.getSharedPreferences("private_state", 0).getString("state", null))
        assertTrue(events.isEmpty())
    }
    @Test fun pairingRequiresAnExplicitNonEmptyLocalNameAtTheEngineBoundary() {
        val link = "https://computer.invalid/#setup=12345678901234567890123456789012&label=Suggestion"
        engine.call("pair", link)
        waitUntil { events.any { it.optString("type") == "failure" } }
        assertEquals("{}", engine.snapshot().toString()); assertNull(vault.read("state"))
        events.clear(); engine.call("pair", link, "  我的测试电脑  ")
        waitUntil { events.any { it.optString("type") == "paired" } }
        val state = engine.snapshot(); val relay = state.getJSONArray("relays").getJSONObject(0)
        assertEquals("我的测试电脑", relay.getString("label"))
        assertEquals("wss://computer.invalid", relay.getString("url"))
        assertEquals("bootstrap", state.getJSONObject("auth").getJSONObject("relays").getJSONObject(relay.getString("id")).getString("id"))
        assertFalse(state.getJSONObject("auth").toString().contains("我的测试电脑"))
    }
    @Test fun connectedRenamePreservesTheSameEncryptedSessionAndPendingRequest() {
        MockTcpMonitorRelay().use { fixture ->
            seed(fixture.state()); engine.resume()
            waitUntil { events.any { it.optString("type") == "message" && it.optJSONObject("message")?.optString("type") == "agents" } }
            val before = engine.snapshot(); events.clear()
            var reply: JSONObject? = null; var failure: String? = null
            engine.request("fixture", AgentProtocol.command("fixture", fixture.agent("a"), "get_agent_run_state")) { data, error -> reply = data; failure = error }
            engine.renameComputer("fixture", "公司工作站")
            waitUntil { reply != null || failure != null }
            assertNull(failure); assertEquals("working", reply!!.getString("status"))
            assertEquals(1, fixture.connected.get())
            assertTrue(events.none { it.optString("type") in listOf("status", "enrolled", "removed") })
            val after = engine.snapshot(); after.getJSONArray("relays").getJSONObject(0).put("label", before.getJSONArray("relays").getJSONObject(0).getString("label"))
            assertEquals(before.toString(), after.toString())
            assertTrue(fixture.failures.toString(), fixture.failures.isEmpty())
            engine.pause()
        }
    }
}
