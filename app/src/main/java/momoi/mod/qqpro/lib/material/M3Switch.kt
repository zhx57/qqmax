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
import momoi.mod.qqpro.lib.dpf

/**
 * A self-drawn Material 3 switch (no com.google.android.material dependency — it crashes at the watch
 * theme/DPI; and the base app's native toggle can't be themed). Pill track + circular thumb, animated,
 * colored from the live [M3] tokens so it follows the user's theme accent. Implements [Checkable].
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

    private val w = 44.dp
    private val h = 26.dp
    private val pad = 2.5f.dpf

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
            duration = 180
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

        // MIUIX Track: off = soft muted capsule (dark: 0x2E_FFFFFF, light: 0xFF_E3E3E5); on = MIUI primary blue
        val offTrackColor = if (M3.luminance(M3.surface) > 0.5f) 0xFF_E3E3E5.toInt() else 0x33_FFFFFF
        trackPaint.color = argb.evaluate(pos, offTrackColor, M3.primary) as Int
        rect.set(cx, cy, cx + tw, cy + th)
        val r = th / 2f
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // MIUIX Thumb: pure white circular pill knob across both on/off states
        val thumbR = th / 2f - pad
        val travel = tw - th
        val thumbCx = cx + r + travel * pos
        thumbPaint.color = 0xFF_FFFFFF.toInt()
        canvas.drawCircle(thumbCx, cy + r, thumbR, thumbPaint)
    }
}
