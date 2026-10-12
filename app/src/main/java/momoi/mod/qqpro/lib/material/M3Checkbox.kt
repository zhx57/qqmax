package momoi.mod.qqpro.lib.material

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import momoi.mod.qqpro.lib.dp

/**
 * A self-drawn Miuix (HyperOS) checkbox graphic, used as a [CompoundButton]'s button drawable so the
 * native checkbox keeps all of its selection logic but reads as Miuix (no com.google.android.material
 * dependency, which renders wrong at the watch DPI). Colors are the upstream Miuix `Checkbox`
 * Defaults: checked = [M3.primary] circle + white check; unchecked = [M3.secondary] filled circle
 * (empty); disabled uses the Miuix disabled roles.
 *
 * Sized to its bounds (the selector's QUICheckBox is 16dp; Miuix's own checkbox is 26dp), so it
 * never clips.
 */
class M3CheckDrawable : Drawable() {
    private var checked = false
    private var enabled = true
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val check = MaterialSymbol(MaterialSymbols.check, M3.onPrimary, insetFraction = 0.2f)

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val c = state.any { it == android.R.attr.state_checked }
        val e = state.any { it == android.R.attr.state_enabled }
        if (c == checked && e == enabled) return false
        checked = c; enabled = e
        // Recolor the check glyph here (NOT in draw, which would invalidate-loop).
        check.recolor(if (enabled) M3.onPrimary else M3.disabledOnPrimary)
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(b: Rect) { check.bounds = b }

    override fun getIntrinsicWidth() = 18.dp
    override fun getIntrinsicHeight() = 18.dp

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        if (checked) {
            // Miuix Checkbox: primary-filled circle with an onPrimary check.
            fill.color = if (enabled) M3.primary else M3.disabledPrimary
            canvas.drawOval(rect, fill)
            check.draw(canvas)
        } else {
            // Miuix Checkbox unchecked: secondary-filled circle (no outline).
            fill.color = if (enabled) M3.secondary else M3.disabledSecondary
            canvas.drawOval(rect, fill)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
