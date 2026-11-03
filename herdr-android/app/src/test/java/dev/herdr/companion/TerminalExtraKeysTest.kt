package dev.herdr.companion

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],qualifiers="w360dp-h800dp-xhdpi")
class TerminalExtraKeysTest {
    private fun buttons(v:View):List<Button> = (if(v is Button)listOf(v)else emptyList()) + if(v is ViewGroup)(0 until v.childCount).flatMap{buttons(v.getChildAt(it))}else emptyList()
    @Test fun backspaceAndPagingAreVisibleTouchSizedAndDisabledForReaders() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get();val sent=mutableListOf<String>()
        val keys=TerminalExtraKeys(activity){sent+=it};activity.setContentView(keys)
        try {
            keys.measure(View.MeasureSpec.makeMeasureSpec(AgentStyle.dp(activity,328),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));keys.layout(0,0,keys.measuredWidth,keys.measuredHeight)
            assertEquals(12,buttons(keys).size)
            buttons(keys).forEach { assertTrue("${it.text}: ${it.width}",it.width>=AgentStyle.dp(activity,48));assertTrue(it.height>=AgentStyle.dp(activity,48));assertFalse(it.isEnabled);it.performClick() }
            assertTrue(sent.isEmpty())
            keys.setAvailable(true)
            buttons(keys).first{it.text=="退格"}.performClick();buttons(keys).first{it.text=="PgUp"}.performClick();buttons(keys).first{it.text=="PgDn"}.performClick()
            assertEquals(listOf("BSpace","PPage","NPage"),sent)
            keys.setCompact(true)
            assertTrue(buttons(keys).first{it.text=="退格"}.isShown)
            assertFalse(buttons(keys).first{it.text=="PgUp"}.isShown)
            keys.setCompact(false);assertTrue(buttons(keys).first{it.text=="PgUp"}.isShown)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun ctrlAltRequireAnExplicitChoiceAndOldDialogCannotSendAfterDisable() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get();val sent=mutableListOf<String>()
        val keys=TerminalExtraKeys(activity){sent+=it};activity.setContentView(keys);keys.setAvailable(true)
        try {
            buttons(keys).first{it.text=="Ctrl"}.performClick();assertTrue(sent.isEmpty())
            var dialog=ShadowDialog.getLatestDialog() as AlertDialog
            dialog.listView.performItemClick(null,0,0);shadowOf(Looper.getMainLooper()).idle();assertEquals(listOf("C-c"),sent)
            buttons(keys).first{it.text=="Alt"}.performClick();dialog=ShadowDialog.getLatestDialog() as AlertDialog
            keys.setAvailable(false);dialog.listView.performItemClick(null,0,0);shadowOf(Looper.getMainLooper()).idle();assertEquals(listOf("C-c"),sent)
        }finally{controller.pause().stop().destroy()}
    }
}
