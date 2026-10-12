package momoi.mod.qqpro.lib.material

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * A Miuix (HyperOS) list row (= Miuix `BasicComponent`): optional leading view (avatar/icon) + a
 * title / optional summary column + optional trailing view (switch / chevron / badge). Dimensions
 * and colors are the upstream Miuix `BasicComponent` Defaults, verbatim: minHeight 56dp, inside
 * margin 16dp, title = headline1 17sp Medium on [M3.onSurface], summary = body2 14sp on
 * [M3.onSurfaceVariant] (Miuix onSurfaceVariantSummary #99000000 / #80FFFFFF), 8dp start/end gaps.
 *
 *     M3ListItem(ctx).leading(avatar).title("Alice").subtitle("online").trailing(switch)
 *
 * Public on purpose (a @Mixin body referencing it needs it public).
 */
class M3ListItem(ctx: Context) : LinearLayout(ctx) {

    private val textCol = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val titleView = TextView(ctx).apply {
        setTextColor(M3.onSurface); textSize = M3.typeHeadline
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitleView = TextView(ctx).apply {
        setTextColor(M3.onSurfaceVariant); textSize = M3.typeBody2
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private var leadingView: View? = null
    private var trailingView: View? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Miuix BasicComponentDefaults: minHeight 56dp, insideMargin 16dp all around
        minimumHeight = M3.rowMinHeight
        setPadding(M3.rowInset, M3.rowInset / 2, M3.rowInset, M3.rowInset / 2)
        background = M3.ripple(null)
        textCol.addView(titleView)
        textCol.addView(subtitleView)
        addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Compact density: smaller title/summary text + tighter vertical padding. */
    fun dense(): M3ListItem = apply {
        titleView.textSize = 14f
        subtitleView.textSize = 11f
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)
    }

    fun title(text: CharSequence): M3ListItem = apply { titleView.text = text }

    fun subtitle(text: CharSequence?): M3ListItem = apply {
        if (text.isNullOrEmpty()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = text; subtitleView.visibility = View.VISIBLE
        }
    }

    /** Place a leading view (e.g. avatar) at the start; [sizeDp] is its square size (0 = wrap). */
    fun leading(view: View, sizeDp: Int = 36): M3ListItem = apply {
        leadingView?.let { removeView(it) }
        leadingView = view
        val lp = if (sizeDp > 0) LayoutParams(sizeDp.dp, sizeDp.dp) else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.rightMargin = 8.dp
        addView(view, 0, lp)
    }

    /** Place a trailing view (e.g. switch / chevron / badge) at the end. */
    fun trailing(view: View): M3ListItem = apply {
        trailingView?.let { removeView(it) }
        trailingView = view
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.leftMargin = 8.dp
        addView(view, lp)
    }
}
