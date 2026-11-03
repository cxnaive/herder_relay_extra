package dev.herdr.companion

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w360dp-h800dp-xhdpi")
class PocketStyleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun measure(view: View) { view.measure(View.MeasureSpec.makeMeasureSpec(AgentStyle.dp(context, 328), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); view.layout(0,0,view.measuredWidth,view.measuredHeight) }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun readableColorPairsMeetNormalTextContrast() {
        for (background in listOf(AgentStyle.background,AgentStyle.panel,AgentStyle.raised,AgentStyle.selected)) {
            for (foreground in listOf(AgentStyle.ink,AgentStyle.muted,AgentStyle.accent)) assertTrue("Contrast ${ColorUtils.calculateContrast(foreground, background)}", ColorUtils.calculateContrast(foreground,background)>=4.5)
        }
        assertTrue(ColorUtils.calculateContrast(AgentStyle.onAccent,AgentStyle.accent)>=4.5)
    }
    @Test fun compactComposerRetainsFortyEightDpActionsAndEditableDraft() {
        var sent = false; var attached = false; var draft = ""
        val composer = NativeComposerView(context,false,"",{ draft = it },{ attached = true },{},{},{ sent = true })
        composer.setAvailability(true,true); measure(composer)
        assertTrue(composer.height <= AgentStyle.dp(context,112))
        val actions = views(composer).filter { it is ImageButton || it.contentDescription == "选择 Agent 指令" }
        assertTrue(actions.isNotEmpty()); actions.forEach { assertTrue(it.contentDescription.toString(),it.height>=AgentStyle.dp(context,48)); assertTrue("${it.contentDescription} width=${it.width}",it.width>=AgentStyle.dp(context,48)) }
        composer.editor.setText("原始草稿，不改变发送语义"); assertEquals("原始草稿，不改变发送语义",draft)
        composer.sendButton.performClick(); assertTrue(sent)
        actions.single { it.contentDescription == "添加附件" }.performClick(); assertTrue(attached)
    }
    @Test fun runTimerIsCompactButOfflineExplanationRemainsAvailable() {
        val status = AgentRunStatusView(context) {}; status.show(AgentRunPresentation.Display("运行中","本轮 04:32","本轮总用时，包含工具执行和等待时间","working",true)); measure(status)
        assertTrue(status.height<=AgentStyle.dp(context,52)); assertTrue(status.contentDescription.contains("04:32"))
        status.show(AgentRunPresentation.Display("连接中断","上次确认 04:32","无法确认 Agent 是否仍在运行；重连后校准计时","muted")); measure(status)
        assertTrue(views(status).filterIsInstance<TextView>().any { it.visibility==View.VISIBLE && it.text.contains("无法确认") })
    }
}
