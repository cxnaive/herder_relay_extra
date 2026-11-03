package dev.herdr.companion

import android.content.Context
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme

/** The same reading rhythm in the transcript, workspace preview and full-screen reviewer. */
internal class PocketMarkdown(private val context: Context) : AbstractMarkwonPlugin() {
    override fun configureTheme(builder: MarkwonTheme.Builder) {
        builder.linkColor(AgentStyle.accent).isLinkUnderlined(true)
            .blockMargin(AgentStyle.dp(context, 12)).bulletWidth(AgentStyle.dp(context, 4))
            .headingTypeface(AgentStyle.medium).headingTextSizeMultipliers(floatArrayOf(1.5f, 1.3f, 1.15f, 1.1f, 1f, 1f))
            .headingBreakHeight(0).thematicBreakColor(AgentStyle.border)
            .codeTextColor(AgentStyle.ink).codeBlockTextColor(AgentStyle.ink)
            .codeBackgroundColor(AgentStyle.raised).codeBlockBackgroundColor(AgentStyle.panel)
            .blockQuoteColor(AgentStyle.border).blockQuoteWidth(AgentStyle.dp(context, 1))
    }
}
