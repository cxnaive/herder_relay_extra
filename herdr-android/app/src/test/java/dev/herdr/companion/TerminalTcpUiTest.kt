package dev.herdr.companion

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w400dp-h850dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalTcpUiTest {
    private val context get()=RuntimeEnvironment.getApplication() as Context
    @Before fun setup() { SharedRelay.resetForTest();for(name in listOf("private_state","background-monitor","terminal-appearance"))context.getSharedPreferences(name,0).edit().clear().commit() }
    @After fun cleanup() {SharedRelay.resetForTest()}
    private fun get(owner:Any,name:String)=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))}else emptyList()
    private fun waitUntil(root:View,condition:()->Boolean) {
        val end=System.nanoTime()+15_000_000_000L
        while(System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));root.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1700,View.MeasureSpec.EXACTLY));root.layout(0,0,800,1700);if(condition())return;Thread.sleep(5)}
        fail("terminal UI timeout: ${views(root).filterIsInstance<TextView>().map{it.text}}")
    }
    private fun capture(root: View, name: String, width: Int = 800, height: Int = 1700) {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
        }
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file=File("build/reports/terminal-ui/$name.png");file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun historyUsesActualFramesPreservesScrollAndKeysReachExactRemoteWithoutChangingDraft() {
        MockTcpMonitorRelay().use { fixture ->
            fixture.role="controller";fixture.extraCapabilities+=listOf("pane_realtime_delta","conversation_activity")
            val requests=CopyOnWriteArrayList<JSONObject>();val writes=CopyOnWriteArrayList<JSONObject>();val gate=CountDownLatch(1)
            val fault=AtomicBoolean();val latest=AtomicInteger(1000)
            fixture.foregroundEnvelope={request ->
                requests+=JSONObject(request.toString())
                fun result(data:JSONObject)=JSONObject().put("type","command_result").put("request_id",request.getString("request_id")).put("ok",true).put("data",data)
                when(request.getString("type")) {
                    "get_conversation_history"->result(JSONObject().put("available",true).put("source_revision","r").put("entries",JSONArray()))
                    "get_conversation_updates"->result(JSONObject().put("kind","heartbeat").put("subscription_id","s").put("source_revision","r").put("sequence",1))
                    "read_pane"->{
                        check(request.getString("format")=="ansi");check(!request.has("terminal_columns"));check(request.getJSONObject("target").getString("agent_session_id")=="session-a")
                        val lines=request.getInt("lines");if(lines==1500)check(gate.await(10,TimeUnit.SECONDS))
                        val response=JSONObject().put("type","pane_content").put("request_id",request.getString("request_id")).put("pane_id","a").put("target",request.getJSONObject("target")).put("columns",80).put("no_echo",false)
                        if(fault.getAndSet(false))response.put("error","Temporary terminal read failed").put("content","")
                        else {
                            val from=if(lines==500)501 else 1
                            val body=(from..latest.get()).joinToString("\n"){"history line $it"}
                            response.put("content",body).put("content_fingerprint","$from-${latest.get()}")
                        }
                    }
                    "send_keys","send_text"->{
                        check(request.getJSONObject("target").getString("terminal_id")=="terminal-a");check(request.getString("cwd")=="/fixture/project")
                        writes+=JSONObject(request.toString());result(JSONObject())
                    }
                    "watch_pane","pane_applied","unwatch_pane","unwatch_conversation","release_pane_size"->null
                    else->error("Unexpected command ${request.getString("type")}")
                }
            }
            val shared=SharedRelay.get(context);shared.vault.javaClass.getDeclaredField("key\$delegate").apply{isAccessible=true}.set(shared.vault,lazyOf(SecretKeySpec(ByteArray(32){21},"AES")))
            shared.vault.write("state",fixture.state().toString());shared.engine.javaClass.getDeclaredField("state").apply{isAccessible=true}.set(shared.engine,fixture.state())
            val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=controller.get();val root=activity.window.decorView
            try {
                waitUntil(root){shared.ready("fixture")}
                val ui=get(activity,"nativeAgents") as NativeAgents;ui.open("fixture",fixture.agent("a"))
                waitUntil(root){views(root).any{it.contentDescription=="终端"}}
                views(root).first{it.contentDescription=="终端"}.performClick()
                waitUntil(root){(get(ui,"content") as String).contains("history line 1000") && !(get(ui,"terminalReadPending") as Boolean)}
                val viewport=get(ui,"terminalScroll") as TerminalViewport
                assertTrue(viewport.output.height>viewport.height*5)
                viewport.restorePosition(300,false);waitUntil(root){viewport.scrollY==300}
                val oldHeight=viewport.output.height
                (get(ui,"terminalOlderButton") as Button).performClick()
                waitUntil(root){requests.any{it.optInt("lines")==1500}}
                assertEquals(View.VISIBLE,(get(ui,"terminalHistorySpinner") as ProgressBar).visibility)
                assertFalse((get(ui,"terminalOlderButton") as Button).isEnabled)
                assertTrue(views(get(ui,"terminalKeys") as View).filterIsInstance<Button>().none { it.isEnabled })
                capture(root,"history-loading")
                gate.countDown()
                waitUntil(root){(get(ui,"content") as String).startsWith("history line 1\n") && !(get(ui,"loadingOlderTerminal") as Boolean)}
                waitUntil(root){viewport.scrollY==300+viewport.output.height-oldHeight}
                val reading=viewport.scrollY
                latest.set(1020)
                val read=ui.javaClass.getDeclaredMethod("readPane",Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
                read.invoke(ui,false,false)
                waitUntil(root){(get(ui,"content") as String).contains("history line 1020")}
                assertEquals(reading,viewport.scrollY)
                val watchesBeforeError=requests.count { it.optString("type")=="watch_pane" }
                fault.set(true);read.invoke(ui,false,false)
                waitUntil(root){(get(ui,"terminalHistoryMessage") as String).contains("Temporary")}
                assertTrue((get(ui,"content") as String).contains("history line 1\n"))
                waitUntil(root){requests.count { it.optString("type")=="watch_pane" }>watchesBeforeError}
                val preserved=get(ui,"content") as String
                val lastRead=requests.last { it.optString("type")=="read_pane" }
                val stale=JSONObject().put("type","pane_content").put("request_id",lastRead.getString("request_id")).put("target",lastRead.getJSONObject("target")).put("pane_id","a").put("content","late stale output")
                ui.onEvent(JSONObject().put("type","message").put("relayId","fixture").put("message",stale))
                assertEquals(preserved,get(ui,"content"))
                (get(ui,"terminalOlderButton") as Button).performClick()
                waitUntil(root){(get(ui,"terminalHistoryMessage") as String).contains("只返回")}
                assertTrue(writes.isEmpty())
                assertFalse(get(ui,"terminalHasMore") as Boolean)
                val draft=(get(ui,"composerPanel") as NativeComposerView).editor;draft.setText("local draft")
                val keys=get(ui,"terminalKeys") as TerminalExtraKeys
                fun press(label:String) { waitUntil(root){views(keys).filterIsInstance<Button>().first{it.text==label}.isEnabled};views(keys).filterIsInstance<Button>().first{it.text==label}.performClick() }
                press("退格");waitUntil(root){writes.size==1};assertEquals("Backspace",writes[0].getJSONArray("keys").getString(0));assertEquals("local draft",draft.text.toString())
                press("Enter");waitUntil(root){writes.size==2};assertEquals("Enter",writes[1].getJSONArray("keys").getString(0));assertEquals("local draft",draft.text.toString())
                press("PgUp");waitUntil(root){writes.size==3};assertEquals("send_text",writes[2].getString("type"));assertEquals("\u001b[5~",writes[2].getString("text"))
                press("Ctrl");val dialog=ShadowDialog.getLatestDialog() as AlertDialog;dialog.listView.performItemClick(dialog.listView.getChildAt(6),6,6)
                waitUntil(root){writes.size==4};assertEquals("ctrl+w",writes[3].getJSONArray("keys").getString(0))
                waitUntil(root){!get(ui,"terminalReadPending").let { it as Boolean }}
                viewport.restorePosition(300,false);waitUntil(root){viewport.scrollY==300}
                capture(root,"terminal-controls")
                capture(root,"terminal-compact",720,1050)
                assertTrue("hidden extra keys must release screen height",keys.height<=AgentStyle.dp(context,52))
                assertFalse(views(keys).filterIsInstance<Button>().first { it.text=="PgUp" }.isShown)
                assertTrue(views(keys).filterIsInstance<Button>().first { it.text=="退格" }.isShown)
                val menu=ui.javaClass.getDeclaredMethod("inputMenu").apply{isAccessible=true};menu.invoke(ui)
                val compactMenu=ShadowDialog.getLatestDialog() as AlertDialog
                val menuLabels=(0 until compactMenu.listView.adapter.count).map { compactMenu.listView.adapter.getItem(it).toString() }
                assertTrue(menuLabels.any { it.startsWith("↑") });assertTrue(menuLabels.any { it.startsWith("Page Up") });compactMenu.dismiss()
                val resources=context.resources;val oldConfig=android.content.res.Configuration(resources.configuration)
                try {
                    @Suppress("DEPRECATION")
                    resources.updateConfiguration(android.content.res.Configuration(oldConfig).apply { fontScale=1.3f },resources.displayMetrics)
                    ui.javaClass.getDeclaredMethod("render").apply { isAccessible=true }.invoke(ui)
                    waitUntil(root){!get(ui,"terminalReadPending").let { it as Boolean }}
                    capture(root,"terminal-font130",720,1600)
                } finally { @Suppress("DEPRECATION") resources.updateConfiguration(oldConfig,resources.displayMetrics) }
                // A menu from the old render must be inert, even when the same Agent is selected.
                assertTrue(views(keys).filterIsInstance<Button>().none { it.isEnabled })
                val delivery=ui.javaClass.getDeclaredMethod("deliveryKey").apply{isAccessible=true}.invoke(ui) as String
                shared.vault.write(delivery,"unconfirmed")
                ui.javaClass.getDeclaredMethod("updateSendState").apply{isAccessible=true}.invoke(ui)
                val uncertainKeys=get(ui,"terminalKeys") as View
                assertTrue(views(uncertainKeys).filterIsInstance<Button>().none { it.isEnabled })
                val beforeBlockedRead=requests.count { it.optString("type")=="read_pane" }
                read.invoke(ui,true,true);shadowOf(Looper.getMainLooper()).idle()
                assertEquals(beforeBlockedRead,requests.count { it.optString("type")=="read_pane" })
                shared.vault.remove(delivery);ui.javaClass.getDeclaredMethod("updateSendState").apply{isAccessible=true}.invoke(ui)
                views(uncertainKeys).filterIsInstance<Button>().first { it.text=="Alt" }.performClick();val oldDialog=ShadowDialog.getLatestDialog() as AlertDialog
                ui.open("fixture",fixture.agent("b"))
                oldDialog.listView.performItemClick(oldDialog.listView.getChildAt(0),0,0);shadowOf(Looper.getMainLooper()).idle();assertEquals(4,writes.size)
                assertTrue(fixture.failures.toString(),fixture.failures.isEmpty())
            }finally{gate.countDown();controller.pause().stop().destroy()}
        }
    }
}
