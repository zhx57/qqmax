package momoi.mod.qqpro.lib.material

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import momoi.mod.qqpro.lib.HorizontalDragWidget
import momoi.mod.qqpro.lib.dp

/**
 * A self-drawn Miuix (HyperOS) slider (no com.google.android.material dependency — the watch theme
 * isn't MaterialComponents-based). A rounded inactive track ([M3.sliderBackground]) with a rounded
 * active track ([M3.primary]) from the left, and a white onPrimary knob; drag anywhere to scrub.
 * Colors are the upstream Miuix `SliderDefaults.sliderColors()`; geometry is Miuix: minHeight 28dp,
 * track = barHeight capsule, knob radius 0.72 of the bar half-height.
 *
 * Mirrors the slice of [android.widget.SeekBar] the video player needs: [max] / [progress] plus
 * start / change / stop callbacks. Setting [progress] programmatically does NOT fire a callback.
 *
 * Implements [HorizontalDragWidget] so [momoi.mod.qqpro.lib.SwipeBackLayout] won't steal a horizontal
 * drag that starts on it. Public on purpose (referenced from non-Mixin player code).
 */
@SuppressLint("ClickableViewAccessibility")
class M3Slider(ctx: Context) : View(ctx), HorizontalDragWidget {

    var max: Int = 100
        set(value) { field = value.coerceAtLeast(0); invalidate() }

    /** Current progress in [0, max]. Setting programmatically does NOT fire [onProgressChanged]. */
    var progress: Int = 0
        set(value) { field = value.coerceIn(0, max); invalidate() }

    var activeColor: Int = M3.primary
        set(value) { field = value; invalidate() }
    var inactiveColor: Int = M3.sliderBackground
        set(value) { field = value; invalidate() }
    var thumbColor: Int = M3.onPrimary
        set(value) { field = value; invalidate() }

    var onStartTracking: (() -> Unit)? = null
    var onProgressChanged: ((progress: Int, fromUser: Boolean) -> Unit)? = null
    var onStopTracking: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var tracking = false

    // Miuix Slider: MinHeight = 28dp — the bar (track) is the full height capsule.
    private val defaultH = 28.dp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(defaultH, heightMeasureSpec),
        )
    }

    private fun fraction(): Float = if (max <= 0) 0f else progress.toFloat() / max

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        // Miuix slider geometry: the track is a bar-height capsule; the knob is 0.72 of the bar
        // half-height (thumbRadius = barHeight/2), riding inset by the bar radius at both ends.
        val thumbRadius = (height - paddingTop - paddingBottom) / 2f
        val thumbR = thumbRadius * 0.72f
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        if (right <= left) return
        val cx = left + (right - left) * fraction()
        val r = thumbRadius
        // Inactive track (full width).
        paint.color = inactiveColor
        rect.set(left - r, cy - r, right + r, cy + r)
        canvas.drawRoundRect(rect, r, r, paint)
        // Active track (left → thumb).
        paint.color = activeColor
        rect.set(left - r, cy - r, cx, cy + r)
        canvas.drawRoundRect(rect, r, r, paint)
        // Knob (white onPrimary, 0.72 of half-height).
        paint.color = thumbColor
        canvas.drawCircle(cx, cy, thumbR, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onStartTracking?.invoke()
                updateFromTouch(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (tracking) updateFromTouch(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    tracking = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onStopTracking?.invoke()
                }
            }
        }
        return true
    }

    private fun updateFromTouch(x: Float) {
        val thumbRadius = (height - paddingTop - paddingBottom) / 2f
        val left = paddingLeft + thumbRadius
        val right = width - paddingRight - thumbRadius
        val f = if (right <= left) 0f else ((x - left) / (right - left)).coerceIn(0f, 1f)
        val newProgress = (f * max).toInt()
        if (newProgress != progress) {
            progress = newProgress
            onProgressChanged?.invoke(newProgress, true)
        } else {
            invalidate()
        }
    }
}
