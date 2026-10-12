package momoi.mod.qqpro.lib.material

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.Checkable
import momoi.mod.qqpro.lib.dp

/**
 * A self-drawn Miuix (HyperOS) switch. Dimensions and colors are the upstream Miuix
 * `Switch` Defaults, verbatim: 49x28dp capsule track, 20dp thumb, thumb offset animates
 * 4dp (off) to 25dp (on); checked track = [M3.primary] + white thumb, unchecked track =
 * [M3.secondary] + white thumb (Miuix onPrimary = onSecondary = white); disabled tracks
 * use the Miuix disabled roles. Implements [Checkable] so it can back native toggle rows.
 *
 * Use [setChecked] (with notify=false to seed initial state without firing [onChange]) and set
 * [onChange] for the toggle callback. Public on purpose (referenced from @Mixin bodies).
 */
class M3Switch(ctx: Context) : View(ctx), Checkable {

    /** Fired on user toggle (and on programmatic [toggle]); NOT fired by `setChecked(v, notify=false)`. */
    var onChange: ((Boolean) -> Unit)? = null

    private var checkedState = false
    private var pos = 0f                  // 0 = off, 1 = on (animated)
    private val argb = ArgbEvaluator()
    private var anim: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val w = M3.switchW            // 49dp — Miuix Switch track width
    private val h = M3.switchH            // 28dp — Miuix Switch track height
    private val thumb = M3.switchThumb    // 20dp — Miuix Switch thumb size
    private val offOffset = 4.dp          // Miuix thumb offset when off
    private val onOffset = 25.dp          // Miuix thumb offset when on

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun isChecked(): Boolean = checkedState

    override fun setChecked(checked: Boolean) = setChecked(checked, notify = false)

    /** Set state; [notify] controls whether [onChange] fires (false when seeding initial value). */
    fun setChecked(checked: Boolean, notify: Boolean) {
        if (checkedState == checked) return
        checkedState = checked
        animateTo(if (checked) 1f else 0f)
        if (notify) onChange?.invoke(checked)
    }

    override fun toggle() = setChecked(!checkedState, notify = true)

    private fun animateTo(target: Float) {
        anim?.cancel()
        if (!isLaidOut) { pos = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 200
            interpolator = M3Motion.EasingEmphasized
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = paddingLeft.toFloat()
        val cy = paddingTop.toFloat()
        val tw = (width - paddingLeft - paddingRight).toFloat()
        val th = (height - paddingTop - paddingBottom).toFloat()
        val enabled = isEnabled

        // Miuix SwitchDefaults: checked track = primary, unchecked track = secondary; the color
        // blends across the thumb animation. Disabled tracks use the Miuix disabled roles.
        val onTrack = if (enabled) M3.primary else M3.disabledPrimary
        val offTrack = if (enabled) M3.secondary else M3.disabledSecondary
        trackPaint.color = argb.evaluate(pos, offTrack, onTrack) as Int
        rect.set(cx, cy, cx + tw, cy + th)
        val r = th / 2f
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // Miuix thumb: white in both states (onPrimary = onSecondary = white), riding the
        // 4dp→25dp left-offset rail (thumb left = offset, so center = offset + thumb/2).
        val thumbLeft = cx + offOffset + (onOffset - offOffset) * pos
        thumbPaint.color = if (enabled) M3.onSecondary else M3.disabledOnSurface
        canvas.drawCircle(thumbLeft + thumb / 2f, cy + r, thumb / 2f, thumbPaint)
    }
}
