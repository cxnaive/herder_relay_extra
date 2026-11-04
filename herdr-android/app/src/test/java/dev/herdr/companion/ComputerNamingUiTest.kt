package dev.herdr.companion

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComputerNamingUiTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context
    @Before fun setup() {
        SharedRelay.resetForTest()
        for (name in listOf("private_state", "background-monitor", "file-browser", AgentGroupExpansion.PREFERENCES)) app.getSharedPreferences(name, 0).edit().clear().commit()
    }
    @After fun cleanup() { SharedRelay.resetForTest() }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun get(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Synthetic naming UI operation timed out")
    }
    private inner class Fixture : AutoCloseable {
        val shared = SharedRelay.get(app).also { set(it.vault, "key\$delegate", lazyOf(SecretKeySpec(ByteArray(32) { 25 }, "AES"))) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root get() = activity.window.decorView
        init { shared.engine.pause(); shadowOf(Looper.getMainLooper()).idle() }
        fun tab(name: String) { activity.javaClass.getDeclaredMethod("changeTab", String::class.java).apply { isAccessible = true }.invoke(activity, name); layout(root) }
        fun seed() {
            val rows = JSONArray(); val credentials = JSONObject()
            for (id in listOf("first", "second")) {
                rows.put(JSONObject().put("id", id).put("label", "原名称").put("paired", true))
                credentials.put(id, JSONObject().put("kind", "credential").put("secret", "synthetic-$id").put("role", "reader"))
            }
            val state = JSONObject().put("relays", rows).put("auth", JSONObject().put("version", 1).put("relays", credentials))
            shared.vault.write("state", state.toString()); set(shared.engine, "state", state)
            shared.event(JSONObject().put("type", "persist"))
        }
        fun openLink(label: String? = null) {
            val link = "herdr-tcp://computer.invalid:8377/#invite=synthetic-invite-12345&invite_version=1&setup=${RelayCrypto.encode(ByteArray(32) { 26 })}" +
                "&invite_expires=${System.currentTimeMillis() + 600000}&rendezvous=${RelayCrypto.encode(ByteArray(32) { 27 })}&relay_id=${RelayCrypto.encode(ByteArray(16) { 28 })}" +
                (label?.let { "&label=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "")
            controller.newIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, link))
        }
        fun dialog(): AlertDialog { shadowOf(Looper.getMainLooper()).idle(); return ShadowAlertDialog.getLatestAlertDialog().also { assertTrue(it.isShowing) } }
        fun name(dialog: AlertDialog) = views(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        fun button(text: String) = views(root).filterIsInstance<android.widget.Button>().first { it.text.toString() == text }
        override fun close() { controller.pause().stop().destroy(); shared.engine.pause() }
    }
    private fun layout(root: View) {
        val width = AgentStyle.dp(root.context, 360); val height = AgentStyle.dp(root.context, 800)
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
    }
    private fun capture(dialog: AlertDialog, name: String) {
        val root = dialog.window!!.decorView
        val width = AgentStyle.dp(root.context, 336); val height = AgentStyle.dp(root.context, 680)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/computer-naming/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown)
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).height >= AgentStyle.dp(root.context, 48))
    }
    @Test fun pairingWithoutSuggestedNameRequiresInputAndCancelDoesNotSaveOrConnect() = Fixture().use { f ->
        f.openLink()
        val dialog = f.dialog(); val name = f.name(dialog)
        assertEquals("", name.text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing); assertEquals("请输入电脑名称", name.error.toString())
        assertEquals("{}", f.shared.engine.snapshot().toString()); assertNull(f.shared.vault.read("state"))
        name.setText("x".repeat(49)); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing); assertNotNull(name.error)
        name.setText("公司电脑"); dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing); assertEquals("{}", f.shared.engine.snapshot().toString())
        assertTrue((get(f.shared.engine, "connections") as Map<*, *>).isEmpty())
    }
    @Test fun pairingSuggestsButDoesNotRequireTheInvitationLabelAndSavesOnlyTheChosenLocalName() = Fixture().use { f ->
        f.openLink("链接里的工作站")
        val dialog = f.dialog(); val name = f.name(dialog)
        assertEquals("链接里的工作站", name.text.toString())
        val visible = views(dialog.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(visible.contains("computer.invalid")); assertTrue(visible.contains("仅用于当前手机"))
        assertFalse(visible.contains("invite=")); assertFalse(visible.contains("synthetic-invite")); assertFalse(visible.contains(RelayCrypto.encode(ByteArray(32) { 26 })))
        name.setText("  家里台式机  "); capture(dialog, "pair-computer")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        waitUntil { f.shared.engine.snapshot().optJSONArray("relays")?.length() == 1 }
        val state = f.shared.engine.snapshot(); val relay = state.getJSONArray("relays").getJSONObject(0)
        assertEquals("家里台式机", relay.getString("label")); assertEquals("tcp://computer.invalid:8377", relay.getString("gatewayUrl"))
        assertEquals("synthetic-invite-12345", state.getJSONObject("auth").getJSONObject("relays").getJSONObject(relay.getString("id")).getString("id"))
        assertFalse(state.getJSONObject("auth").toString().contains("家里台式机"))
        assertEquals(state.toString(), f.shared.vault.read("state")); assertFalse(dialog.isShowing)
        assertTrue((get(f.shared.engine, "connections") as Map<*, *>).isEmpty())
    }
    @Test fun pasteFlowAlsoRequiresNamingAndInvalidLinksNeverReachTheNameDialog() = Fixture().use { f ->
        f.tab("devices"); f.button("粘贴链接").performClick()
        val paste = f.dialog(); f.name(paste).setText("https://computer.invalid/#setup=12345678901234567890123456789012&label=Paste")
        paste.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val naming = f.dialog(); assertNotSame(paste, naming); assertEquals("Paste", f.name(naming).text.toString())
        naming.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
        f.controller.newIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "herdr-tcp://computer.invalid:8377/#invite=invalid"))
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        assertEquals("{}", f.shared.engine.snapshot().toString())
    }
    @Test fun deviceCardRenamesOfflineAndRefreshesBothTheHeaderAndComputerGroupWithoutChangingOtherComputer() = Fixture().use { f ->
        f.seed(); f.tab("devices")
        val before = f.shared.engine.snapshot()
        f.button("重命名").performClick()
        val dialog = f.dialog(); assertEquals("原名称", f.name(dialog).text.toString())
        f.name(dialog).setText("新的公司电脑"); capture(dialog, "rename-computer")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
        val after = f.shared.engine.snapshot()
        assertEquals("新的公司电脑", f.shared.relayName("first")); assertEquals("原名称", f.shared.relayName("second"))
        assertEquals(before.getJSONObject("auth").toString(), after.getJSONObject("auth").toString())
        assertTrue(views(f.root).filterIsInstance<TextView>().any { it.text.toString().startsWith("新的公司电脑  ·") })
        assertTrue(views(f.root).filterIsInstance<TextView>().any { it.text.toString() == "新的公司电脑" })
        assertEquals(1, (get(f.activity, "content") as ViewGroup).childCount)
        assertEquals(1, views(f.root).filterIsInstance<TextView>().count { it.text.toString() == "原名称" })
        f.tab("agents")
        val group = views(f.root).filterIsInstance<AgentGroupHeader>().single { it.groupKey == AgentGroupExpansion.computerKey("first") }
        assertTrue(group.contentDescription.toString().contains("新的公司电脑"))
        val search = views(f.root).filterIsInstance<EditText>().single(); search.setText("新的公司电脑"); layout(f.root)
        assertEquals(listOf(AgentGroupExpansion.computerKey("first")), views(f.root).filterIsInstance<AgentGroupHeader>().map { it.groupKey })
    }
    @Test fun renamingWhileTheListIsOpenRetainsItsCollapsedStateAndSearchField() = Fixture().use { f ->
        f.seed(); f.tab("agents")
        val key = AgentGroupExpansion.computerKey("first")
        views(f.root).filterIsInstance<AgentGroupHeader>().single { it.groupKey == key }.performClick(); layout(f.root)
        val saved = app.getSharedPreferences(AgentGroupExpansion.PREFERENCES, 0).all.toString()
        val search = views(f.root).filterIsInstance<EditText>().single()
        f.shared.engine.renameComputer("first", "家里的电脑"); layout(f.root)
        val renamed = views(f.root).filterIsInstance<AgentGroupHeader>().single { it.groupKey == key }
        assertFalse(renamed.expanded); assertTrue(renamed.contentDescription.toString().contains("家里的电脑"))
        assertSame(search, views(f.root).filterIsInstance<EditText>().single())
        assertEquals(saved, app.getSharedPreferences(AgentGroupExpansion.PREFERENCES, 0).all.toString())
        assertFalse(AgentGroupExpansion(app).expanded(key, ""))
    }
    @Test fun staleRenameDialogCannotRecreateARemovedComputer() = Fixture().use { f ->
        f.seed(); f.tab("devices"); f.button("重命名").performClick()
        val dialog = f.dialog(); f.name(dialog).setText("新的名称")
        f.shared.engine.call("remove", "first"); shadowOf(Looper.getMainLooper()).idle()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing); assertTrue(f.name(dialog).error.toString().contains("已被移除"))
        assertEquals(1, f.shared.engine.snapshot().getJSONArray("relays").length())
        assertEquals("second", f.shared.engine.snapshot().getJSONArray("relays").getJSONObject(0).getString("id"))
        dialog.dismiss()
    }
    @Test fun cancelledRenameAndSaveFailureKeepTheOriginalNameAndDialogInput() = Fixture().use { f ->
        f.seed(); f.tab("devices"); f.button("重命名").performClick()
        val cancelled = f.dialog(); f.name(cancelled).setText("不保存的名称")
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
        cancelled.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("原名称", f.shared.relayName("first"))
        f.button("重命名").performClick()
        val dialog = f.dialog(); val input = f.name(dialog); input.setText("待保存的名称")
        val before = f.shared.engine.snapshot().toString()
        set(f.shared.vault, "key\$delegate", lazy { error("Synthetic storage failure") })
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.isShowing); assertEquals("待保存的名称", input.text.toString())
        assertEquals("无法保存电脑名称，请重试", input.error.toString()); assertEquals(before, f.shared.engine.snapshot().toString())
        dialog.dismiss()
    }
    @Test fun renamingDuringAConversationUpdatesItsIdentityWithoutRecreatingTheComposerOrHistory() = Fixture().use { f ->
        f.seed()
        val agent = JSONObject().put("pane_id", "pane-a").put("tab_id", "tab-a").put("tab_label", "writer")
            .put("terminal_id", "term-a").put("agent_session_id", "session-a").put("generation", 1)
            .put("workspace_id", "project").put("server_session_id", "primary").put("cwd", "/fixture/project").put("agent", "codex")
        f.shared.event(JSONObject().put("type", "message").put("relayId", "first").put("message", JSONObject().put("type", "agents").put("agents", JSONArray().put(agent))))
        f.tab("agents")
        val ui = get(f.activity, "nativeAgents") as NativeAgents; ui.open("first", agent); layout(f.root)
        val editor = (get(ui, "composerPanel") as NativeComposerView).editor; editor.setText("不要丢失这个草稿")
        val history = get(ui, "conversation")
        f.shared.engine.renameComputer("first", "改名后的电脑"); layout(f.root)
        assertSame(editor, (get(ui, "composerPanel") as NativeComposerView).editor)
        assertSame(history, get(ui, "conversation")); assertEquals("不要丢失这个草稿", editor.text.toString())
        assertTrue((get(ui, "identitySubtitle") as TextView).text.toString().startsWith("改名后的电脑 ·"))
    }
    @Test fun narrowLargeTextKeepsNamingAndDeviceActionsVisible() = Fixture().use { f ->
        val configuration = android.content.res.Configuration(f.activity.resources.configuration).apply { fontScale = 1.3f }
        @Suppress("DEPRECATION") f.activity.resources.updateConfiguration(configuration, f.activity.resources.displayMetrics)
        f.seed(); f.shared.engine.renameComputer("first", "家里的研究工作站与训练服务器·名称较长"); f.tab("devices")
        val rename = f.button("重命名")
        assertTrue(rename.width >= AgentStyle.dp(f.activity, 48)); assertTrue(rename.height >= AgentStyle.dp(f.activity, 48))
        assertEquals(1, rename.layout.lineCount); assertEquals(0, rename.layout.getEllipsisCount(0))
        val viewAgents = f.button("查看 Agents")
        assertTrue("Device action text must not be clipped at large font sizes", viewAgents.layout.height <= viewAgents.height - viewAgents.compoundPaddingTop - viewAgents.compoundPaddingBottom)
        val bitmap = Bitmap.createBitmap(f.root.width, f.root.height, Bitmap.Config.ARGB_8888); f.root.draw(Canvas(bitmap))
        val file = File("build/reports/computer-naming/devices-large-text.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        rename.performClick(); val dialog = f.dialog(); capture(dialog, "rename-large-text")
        assertEquals("家里的研究工作站与训练服务器·名称较长", f.name(dialog).text.toString())
        dialog.dismiss()
    }

}
