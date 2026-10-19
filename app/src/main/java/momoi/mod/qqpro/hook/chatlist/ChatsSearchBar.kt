package momoi.mod.qqpro.hook.chatlist

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Always-visible search box pinned to the top of the messages page (1st main page).
 *
 * ## Why filtering is done at the LAYOUT level, not by re-submitting the list
 *
 * The conversation list is fed by the kernel: `ChatsListAdapter` is driven by recent-contact
 * pushes (new message, read receipt, top/pin change, delete). Every other search surface in this
 * app filters by calling `submitList(filtered)` and then re-snapshots the adapter — that works for
 * selector lists whose content the app itself owns, but it is wrong here:
 *
 *   • a kernel push arriving mid-search would re-submit the UNFILTERED list, and the next
 *     keystroke would restore the filtered one — a race that flickers rows in and out;
 *   • `ListAdapter.submitList` on this adapter triggers the diff/animations used by the native
 *     list, so filtering through it re-runs the native bind path and its animations;
 *   • the pinned (置顶) rows and the unread badges are computed inside the native bind — feeding it
 *     a synthetic subset changes what it thinks the list looks like.
 *
 * So instead the adapter is left completely untouched: every bound row that doesn't match the
 * query gets `GONE`, matching rows stay visible. Rows are re-evaluated on every scroll/attach, so
 * rows recycled into view are judged correctly, and clearing the query restores everything
 * instantly — with no interaction with kernel pushes at all.
 *
 * The bar itself is an OVERLAY over the list (same construction as [momoi.mod.qqpro.hook.contact.ContactTopBar]).
 * The list is never reparented; it just gets a top padding equal to the bar's height so its first
 * row starts below the bar.
 */
object ChatsSearchBar {
    private const val TAG = "qqpro_chats_search"

    /** Tag index stashing a collapsed row's original height (see [collapse]). */
    private val COLLAPSE_TAG = "qqpro_chats_search_h".hashCode()

    /** The chat list's layout id — same id the native list and [momoi.mod.qqpro.hook.MainNav] use. */
    private const val RV_ID_NAME = "chat_list"

    private var bar: LinearLayout? = null
    private var field: EditText? = null
    private var list: RecyclerView? = null
    private var emptyHint: TextView? = null
    private var query = ""

    /**
     * Wrap [root] (the ChatListFragment's own root, returned from its onCreateView) with the search
     * overlay. Idempotent — a previous wrapper for the same host is torn down first, because the
     * messages page is rebuilt on every return to it.
     */
    fun install(root: View): View {
        val ctx = root.context
        // Already wrapped this exact root (e.g. an extra layout pass): nothing to do.
        if (root.tag == TAG) return root

        val rv = findChatList(root)
        if (rv == null) {
            Utils.log("ChatsSearchBar: chat_list RecyclerView not found, retrying")
            // The list may not be inflated yet; retry a few times off the view queue.
            retry(root, 0)
            return root
        }
        detach()

        val searchBar = buildBar(ctx)
        val hint = buildEmptyHint(ctx)

        val wrap = FrameLayout(ctx).apply {
            tag = TAG
            layoutParams = root.layoutParams
            addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(searchBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
            addView(hint, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        bar = searchBar
        list = rv
        emptyHint = hint

        // Reserve room for the overlay: keep the list's top padding in sync with the bar's real
        // measured height (a one-shot post() reads 0 before the first layout pass).
        rv.clipToPadding = false
        searchBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && rv.paddingTop != h) {
                rv.setPadding(rv.paddingLeft, h, rv.paddingRight, rv.paddingBottom)
            }
        }

        applyFilterToAttachedRows()
        attachScrollListener(rv)
        Utils.log("ChatsSearchBar: installed over ${rv.javaClass.simpleName}")
        return wrap
    }

    private fun retry(root: View, attempt: Int) {
        if (attempt >= 12) {
            Utils.log("ChatsSearchBar: gave up waiting for chat_list")
            return
        }
        root.postDelayed({
            runCatching { install(root) }.onFailure { Utils.log("ChatsSearchBar retry: $it") }
        }, 60)
    }

    /** Tear down state from a previous host so a stale list/bar can't be filtered. */
    private fun detach() {
        clearQuery()
        bar = null
        field = null
        list = null
        emptyHint = null
        query = ""
    }

    /** The conversation list under [root], by layout id (never "first RecyclerView found"). */
    private fun findChatList(root: View): RecyclerView? {
        val id = root.resources.getIdentifier(RV_ID_NAME, "id", root.context.packageName)
        if (id != 0) (root.findViewById<View>(id) as? RecyclerView)?.let { return it }
        return root.findAllRecyclerViews().firstOrNull()
    }

    private fun View.findAllRecyclerViews(): List<RecyclerView> {
        val out = ArrayList<RecyclerView>()
        fun walk(v: View) {
            if (v is RecyclerView) { out.add(v); return }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }

    // ── bar construction ────────────────────────────────────────────────────────────────────────

    private fun buildBar(ctx: Context): LinearLayout {
        val et = EditText(ctx).apply {
            hint = "搜索聊天"
            setHintTextColor(M3.hint)
            setTextColor(M3.onSurface)
            textSize = 13f
            setSingleLine()
            setPadding(10.dp, 6.dp, 10.dp, 6.dp)
            compoundDrawablePadding = 7.dp
            val ic = MaterialSymbol(MaterialSymbols.search, M3.onSurfaceVariant)
                .apply { setBounds(0, 0, 16.dp, 16.dp) }
            setCompoundDrawables(ic, null, null, null)
            background = M3.rounded(M3.surfaceContainerHigh, M3.radiusPill)
            // New-page entry (returning to the messages tab) should not steal focus / raise the
            // keyboard — the bar is a passive input until the user taps it.
            isFocusableInTouchMode = true
        }
        field = et

        val clear = TextView(ctx).apply {
            text = ""
            setPadding(10.dp, 4.dp, 6.dp, 4.dp)
            compoundDrawablePadding = 0
            val ic = MaterialSymbol(MaterialSymbols.close, M3.onSurfaceVariant)
                .apply { setBounds(0, 0, 14.dp, 14.dp) }
            setCompoundDrawables(ic, null, null, null)
            visibility = View.GONE
            setOnClickListener { et.setText("") }
        }

        et.doAfterTextChanged {
            query = et.text?.toString()?.trim().orEmpty()
            clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            applyFilterToAttachedRows()
            invalidateRows()
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 8.dp, 12.dp, 6.dp)
            addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(clear, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 4.dp })
        }
        // The bar sits on an opaque M3 surface strip so rows scrolling underneath never show through.
        row.background = GradientDrawable().apply { setColor(M3.surface) }
        return row
    }

    private fun buildEmptyHint(ctx: Context): TextView = TextView(ctx).apply {
        text = "没有匹配的聊天"
        setTextColor(M3.onSurfaceVariant)
        textSize = 13f
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    // ── filtering ──────────────────────────────────────────────────────────────────────────────

    /**
     * Re-judge every currently attached row. RecyclerView reuses row views, so a row that was
     * hidden for one item must be re-shown when it is rebound to a matching item — hence the check
     * runs on attach as well as on scroll (both funnel through [invalidateRows]).
     *
     * Hidden rows are collapsed to zero height as well as GONE: this adapter's rows are positioned
     * by the layout manager, and a merely-GONE child would leave the slots it occupied as gaps in
     * the list, so the remaining matches wouldn't close up. Collapsing the row and asking the list
     * to re-layout makes the matches read as a contiguous result list.
     */
    private fun applyFilterToAttachedRows() {
        val rv = list ?: return
        var visible = 0
        var changed = false
        for (i in 0 until rv.childCount) {
            val row = rv.getChildAt(i)
            val text = rowSearchText(rv, row)
            val match = query.isEmpty() || text.contains(query, ignoreCase = true)
            if (match) {
                visible++
                if (row.visibility != View.VISIBLE || collapsed(row)) {
                    uncollapse(row)
                    row.visibility = View.VISIBLE
                    changed = true
                }
            } else {
                if (row.visibility != View.GONE || !collapsed(row)) {
                    collapse(row)
                    row.visibility = View.GONE
                    changed = true
                }
            }
        }
        if (changed) rv.requestLayout()
        // Only meaningful once the list has content. When the list is still loading (no children)
        // don't claim "no results".
        emptyHint?.visibility =
            if (query.isNotEmpty() && rv.childCount > 0 && visible == 0) View.VISIBLE else View.GONE
        // Keep the empty hint above the (hidden) rows.
        emptyHint?.let { if (it.visibility == View.VISIBLE) it.bringToFront() }
    }

    // ── row collapsing ─────────────────────────────────────────────────────────────────────────
    // The original height is stashed on the row via a tag index (no extra map, and it travels with
    // the view through recycling) so restoring is exact for every row type.

    private fun collapsed(row: View): Boolean = row.getTag(COLLAPSE_TAG) != null

    private fun collapse(row: View) {
        if (collapsed(row)) return
        row.setTag(COLLAPSE_TAG, row.layoutParams?.height ?: 0)
        val lp = row.layoutParams
        if (lp != null) {
            lp.height = 0
            (lp as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = 0; it.bottomMargin = 0 }
            row.layoutParams = lp
        }
    }

    private fun uncollapse(row: View) {
        val original = row.getTag(COLLAPSE_TAG) as? Int ?: return
        row.setTag(COLLAPSE_TAG, null)
        val lp = row.layoutParams ?: return
        lp.height = original
        row.layoutParams = lp
    }

    /**
     * The searchable text of a bound row.
     *
     * Resolved from the ADAPTER ITEM rather than the row's visible TextViews: the row also renders
     * the time and the message preview, and matching against those would surface chats whose name
     * has nothing to do with the query. The adapter contract here is `getItem(position)` (see
     * [momoi.mod.qqpro.hook.MainNav.cycleChatUnread], which reads the same list the same way);
     * `RecentContactChatItem.a` is the `RecentContactInfo` carrying the chat's display name.
     */
    private fun rowSearchText(rv: RecyclerView, row: View): String {
        val pos = rv.getChildAdapterPosition(row)
        if (pos == RecyclerView.NO_POSITION) return rowFallbackText(row)
        val item = adapterItemAt(rv, pos) ?: return rowFallbackText(row)
        val name = peerNameOf(item)
        return name ?: rowFallbackText(row)
    }

    private fun adapterItemAt(rv: RecyclerView, pos: Int): Any? = runCatching {
        val adapter = rv.adapter ?: return null
        val m = adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
        m.invoke(adapter, pos)
    }.getOrNull()

    /**
     * The chat's display name from a `RecentContactChatItem` (its `a` field is the
     * `RecentContactInfo`). Read reflectively: the item class lives in the patched target APK, and
     * its accessors are R8-minified, so a direct property reference isn't available here.
     */
    private fun peerNameOf(item: Any): String? = runCatching {
        // RecentContactChatItem.a == RecentContactInfo
        val info = item.javaClass.getField("a").get(item) ?: return null
        for (name in listOf("peerName", "peerUid")) {
            val v = runCatching { info.javaClass.getField(name).get(info) as? String }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        null
    }.getOrNull()

    /**
     * Fallback when the adapter item can't be resolved (e.g. mid-diff, or an unfamiliar item
     * type): use the row's own name TextView. The store keeps the chat name on the title view, so
     * pulling every text off the row is the most forgiving option that still matches names.
     */
    private fun rowFallbackText(row: View): String {
        val sb = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) {
                val t = v.text
                if (!TextUtils.isEmpty(t)) { sb.append(t).append('\n') }
            } else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        return sb.toString()
    }

    /** Re-run the filter after the next layout pass (rows attach/rebind during layout). */
    private fun invalidateRows() {
        val rv = list ?: return
        rv.post { runCatching { applyFilterToAttachedRows() }.onFailure { Utils.log("ChatsSearchBar filter: $it") } }
    }

    /** Hook the list so recycled rows are re-judged as they scroll into view. Called by the host hook. */
    fun attachScrollListener(rv: RecyclerView) {
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (query.isNotEmpty()) applyFilterToAttachedRows()
            }
        })
    }

    fun showKeyboard(v: View) {
        runCatching {
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** The installed search field (for hosts that want to focus it), or null when not installed. */
    fun field(): EditText? = field

    /** Drop any active query (used when the page is being torn down). */
    fun clearQuery() {
        query = ""
        runCatching { field?.setText("") }
        runCatching { applyFilterToAttachedRows() }
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
