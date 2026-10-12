package momoi.mod.qqpro.lib.material

import android.content.Context
import android.widget.LinearLayout
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.vertical

/**
 * A MIUIX-styled surface container card: large rounded [M3.surfaceContainer] background, vertical content.
 * Groups settings rows, profile sections, info blocks with MIUIX's signature smooth continuous corners.
 *
 *     M3Card(ctx).content { add<TextView>().text("hi") }
 *
 * Public on purpose (a @Mixin body referencing it needs it public).
 */
class M3Card(ctx: Context) : LinearLayout(ctx) {
    init {
        vertical()
        background = M3.rounded(M3.surfaceContainer, M3.radiusLg)
        setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        clipToOutline = false
    }

    /** Switch to the raised (surfaceContainerHigh) tone. */
    fun raised(): M3Card = apply { background = M3.rounded(M3.surfaceContainerHigh, M3.radiusLg) }

    /** Enable MIUI Folme-style touch recoil for clickable cards. */
    fun enableTouchFeedback(): M3Card = apply {
        M3.attachMiuixTouchFeedback(this, 0.98f)
    }

    /** Inner padding around the card content. */
    fun contentPadding(value: Int): M3Card = apply { setPadding(value, value, value, value) }
}
