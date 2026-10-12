package momoi.mod.qqpro.lib.material

import android.content.Context
import android.view.Gravity
import android.graphics.Typeface
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * A Miuix (HyperOS) button with **spring press-recoil** — on press it scales to 0.96 and on
 * release springs back to 1.0 with a slight overshoot (under-damped [SpringAnimator], not a linear
 * tween). Self-contained: no com.google.android.material dependency (the watch theme isn't
 * MaterialComponents-based, so a real Material button would crash).
 *
 * Dimensions are the upstream Miuix `Button` Defaults, verbatim: minWidth 58dp, minHeight 40dp,
 * cornerRadius 16dp, insideMargin 16x13dp, label 17sp ([M3.typeMain]).
 *
 * Variants (mapped onto Miuix roles):
 *  - [FILLED]            primary container + onPrimary label (ButtonDefaults.buttonColorsPrimary)
 *  - [TONAL]             secondaryVariant + onSecondaryVariant (ButtonDefaults.buttonColors)
 *  - [TEXT]              no container, primary label (ButtonDefaults.textButtonColorsPrimary)
 *  - [OUTLINED]          outline stroke, onSecondaryVariant label
 *  - [ERROR]             error + onError label (destructive)
 *
 * Public on purpose: a @Mixin body referencing it needs it public (else runtime IllegalAccessError).
 */
class M3Button(ctx: Context) : TextView(ctx) {

    enum class Variant { FILLED, TONAL, TEXT, OUTLINED, ERROR }

    private var pressSpring: SpringAnimator? = null
    private val releaseDelayMs = 100L  // small delay so the recoil feels intentional, not a glitch

    init {
        gravity = Gravity.CENTER
        isSingleLine = true
        textSize = M3.typeMain
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        minWidth = M3.buttonMinWidth
        minHeight = M3.buttonMinHeight
        setPadding(M3.buttonInsetH, M3.buttonInsetV, M3.buttonInsetH, M3.buttonInsetV)
        isClickable = true
        isFocusable = true
        // MIUI Folme-style spring press-recoil: 0.95 on touch down, spring back with lively settle
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    scaleX = 0.95f; scaleY = 0.95f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    v.postDelayed(releaseRunnable, releaseDelayMs)
                }
            }
            false // let click listener still fire
        }
        variant(Variant.FILLED)
    }

    private val releaseRunnable = Runnable {
        val v = this
        pressSpring = SpringAnimator()
            .stiffness(M3Motion.SpringStiffnessLow)
            .dampingRatio(M3Motion.SpringDampingMediumBouncy)
            .startFrom(0.95f)
            .apply { animateTo(1f) { s -> v.scaleX = s; v.scaleY = s } }
    }

    fun variant(v: Variant): M3Button = apply {
        val (container, label) = when (v) {
            Variant.FILLED           -> M3.primary to M3.onPrimary
            Variant.TONAL            -> M3.secondaryVariant to M3.onSecondaryVariant
            Variant.TEXT             -> 0 to M3.primary
            Variant.OUTLINED         -> 0 to M3.onSecondaryVariant
            Variant.ERROR            -> M3.error to M3.onColor(M3.error)
        }
        setTextColor(label)
        val base = when (v) {
            Variant.OUTLINED -> M3.outlined(M3.outline, M3.radiusLg)
            Variant.TEXT -> null
            else -> M3.rounded(container, M3.radiusLg)
        }
        background = if (base == null) null else M3.ripple(base)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}
