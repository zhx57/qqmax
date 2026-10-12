package momoi.mod.qqpro.lib.material

import android.content.Context
import android.widget.LinearLayout
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.vertical

/**
 * A Miuix (HyperOS) grouped-content card: a [M3.surfaceContainer] fill with the upstream Miuix
 * `CardDefaults.CornerRadius` (16dp) continuous corners. Groups settings rows, profile sections,
 * info blocks. Self-contained (no XML, no lib dependency).
 *
 *     M3Card(ctx).content { add<TextView>().text("hi") }
 *
 * Public on purpose (a @Mixin body referencing it needs it public).
 */
class M3Card(ctx: Context) : LinearLayout(ctx) {
    init {
        vertical()
        background = M3.rounded(M3.surfaceContainer, M3.radiusLg)  // Miuix CardDefaults: 16dp
        clipToOutline = false
    }

    /** Switch to the raised (surfaceContainerHigh / Miuix secondaryVariant) tone. */
    fun raised(): M3Card = apply { background = M3.rounded(M3.surfaceContainerHigh, M3.radiusLg) }

    /** Enable MIUI Folme-style touch recoil for clickable cards. */
    fun enableTouchFeedback(): M3Card = apply {
        M3.attachMiuixTouchFeedback(this, 0.98f)
    }

    /** Inner padding around the card content (Miuix Card itself inserts none by default). */
    fun contentPadding(value: Int): M3Card = apply { setPadding(value, value, value, value) }
}
