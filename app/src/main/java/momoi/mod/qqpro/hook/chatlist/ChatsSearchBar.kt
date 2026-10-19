package momoi.mod.qqpro.hook.chatlist

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Always-visible search box for the messages page (1st main page).
 *
 * ## Design: a separate result list, never a modified conversation list
 *
 * The first attempt filtered by collapsing the native list's rows in place (height→0 + GONE).
 * That approach is fundamentally wrong on this RecyclerView, and produced exactly the three
 * failures it was bound to produce:
 *
 *   • **nothing ever matched** — the adapter's rows are only re-judged for views that are
 *     currently attached; collapsing them also broke the list's own layout pass, so the
 *     positions the filter read back were stale and nothing was ever reported as a match;
 *   • **swiping scrolled into other pages** — collapsing every row emptied the conversation
 *     list, and the surrounding horizontal pager then had nothing to scroll against, so a drag
 *     crossed page boundaries;
 *   • **the page looked broken afterwards** — row heights, margins and the list's top padding
 *     were all mutated, and the native bind never restores them, so the original list came back
 *     with rows mis-sized and its cards visually merged.
 *
 * So the native list is now treated as READ-ONLY, and the search is a completely separate
 * surface: a result list (this file's own RecyclerView + adapter) overlaid on the list area while
 * a query is active, and removed when the query is cleared. Nothing about the conversation list is
 * ever written — no padding, no row heights, no visibility, no adapter submit. Dismissing the
 * search is therefore guaranteed to restore the page exactly, and a drag inside the results
 * scrolls the results instead of reaching the pager.
 *
 * Rows are read from the conversation adapter (`getItem(position)` → `RecentContactChatItem`,
 * see [momoi.mod.qqpro.hook.MainNav.cycleChatUnread] which reads the same list the same way), and
 * tapping a result opens that chat via the same intent the incoming-message notification uses
 * ([momoi.mod.qqpro.hook.NotificationReply]).
 */
object ChatsSearchBar {
    private const val TAG = "qqpro_chats_search"
    private const val RV_ID_NAME = "chat_list"

    private var field: EditText? = null
    private var resultsAdapter: ResultAdapter? = null
    private var emptyHint: TextView? = null
    private var list: RecyclerView? = null
    private var query = ""

    // ── install ────────────────────────────────────────────────────────────────────────────────

    /**
     * Build the search overlay over the fragment's [root] and return the wrapper to use as the
     * fragment view.
     *
     * Runs during `onCreateView`, where the root has NO parent yet, so `root.layoutParams` is null
     * and `setLayoutParams(null)` would throw — the wrapper therefore carries its own concrete
     * LayoutParams. The conversation list is also not inflated yet at this point, so it is bound
     * later from [bindListWhenReady]; the wrapper structure itself needs no RecyclerView.
     */
    fun install(root: View): View {
        val ctx = root.context
        if (root.tag == TAG) return root
        detach()

        val bar = buildBar(ctx)
        val resultList = buildResults(ctx)
        val hint = buildEmptyHint(ctx)

        // The results surface occupies exactly the same slot as the conversation list (it replaces
        // it visually while a query is active), so the bar never overlaps either of them.
        val resultsLayer = FrameLayout(ctx).apply {
            visibility = View.GONE
            setBackgroundColor(M3.surface)
            addView(resultList, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(hint, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }

        // bar and content stack VERTICALLY (a horizontal split, not an overlay): the search field
        // gets its own row and the list starts below it, so the bar can never cover the first
        // conversation row. The content area is weighted 1 so it takes the remaining height.
        val content = FrameLayout(ctx).apply {
            addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(resultsLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        val wrap = LinearLayout(ctx).apply {
            tag = TAG
            orientation = LinearLayout.VERTICAL
            // The pager will assign its own LayoutParams when it attaches this; until then it must
            // still hold a valid instance (null would throw in setLayoutParams).
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        field = bar.tag as? EditText
        emptyHint = hint
        resultsLayerId = resultsLayer

        bindListWhenReady(wrap, 0)
        Utils.log("ChatsSearchBar: wrapped host root (bar row + content row)")
        return wrap
    }

    /** The overlay that hosts the (own) result list; null until installed. */
    private var resultsLayerId: View? = null

    private fun bindListWhenReady(wrap: View, attempt: Int) {
        val rv = findChatList(wrap)
        if (rv != null) {
            list = rv
            Utils.log("ChatsSearchBar: conversation list resolved (${rv.javaClass.simpleName})")
            return
        }
        if (attempt >= 40) {
            Utils.log("ChatsSearchBar: gave up waiting for chat_list after $attempt attempts")
            return
        }
        wrap.postDelayed({
            runCatching { bindListWhenReady(wrap, attempt + 1) }
                .onFailure { Utils.log("ChatsSearchBar bind retry: $it") }
        }, 50)
    }

    private fun detach() {
        field = null
        resultsAdapter = null
        emptyHint = null
        list = null
        resultsLayerId = null
        query = ""
    }

    /** The conversation list under [root], by layout id only (see the class docs). */
    private fun findChatList(root: View): RecyclerView? {
        val id = root.resources.getIdentifier(RV_ID_NAME, "id", root.context.packageName)
        if (id == 0) return null
        return root.findViewById<View>(id) as? RecyclerView
    }

    // ── search bar ─────────────────────────────────────────────────────────────────────────────

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
            // Tapping the field raises the keyboard; the bar never steals focus on page entry.
            setOnClickListener { showKeyboard(this) }
        }

        val clear = TextView(ctx).apply {
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
            runSearch()
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 8.dp, 12.dp, 6.dp)
            background = GradientDrawable().apply { setColor(M3.surface) }
            addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(clear, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = 4.dp })
            // Hand the field out to the object state without a second constructor path.
            tag = et
        }
    }

    private fun buildResults(ctx: Context): RecyclerView {
        val adapter = ResultAdapter { row -> openChat(row) }
        resultsAdapter = adapter
        return RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            setBackgroundColor(M3.surface)
            clipToPadding = false
            setPadding(0, 4.dp, 0, 8.dp)
            // While results are on screen they must own the drag. The main frame hosts the pages
            // in a horizontal pager; without this, a vertical drag that reaches the top/bottom of
            // the (short) result list is handed to the pager and swipes to another page. Declining
            // the intercept for the duration of every touch keeps the gesture local.
            addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                    rv.parent?.requestDisallowInterceptTouchEvent(true)
                    return false
                }

                override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })
        }
    }

    private fun buildEmptyHint(ctx: Context): TextView = TextView(ctx).apply {
        text = "没有匹配的聊天"
        setTextColor(M3.onSurfaceVariant)
        textSize = 13f
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    // ── searching ──────────────────────────────────────────────────────────────────────────────

    /**
     * Read every conversation from the native adapter and show the matches.
     *
     * The native list is only READ here. When the query is empty the result layer is hidden
     * entirely, so the untouched conversation list is what the user sees again.
     */
    private fun runSearch() {
        val layer = resultsLayerId ?: return
        if (query.isEmpty()) {
            layer.visibility = View.GONE
            resultsAdapter?.submit(emptyList())
            emptyHint?.visibility = View.GONE
            // Give the list keyboard focus back to the conversation list's own state: just drop it.
            field?.clearFocus()
            return
        }
        val rows = collectChats()
        val q = query
        val hits = rows.filter { it.matches(q) }
        layer.visibility = View.VISIBLE
        layer.bringToFront()
        resultsAdapter?.submit(hits)
        emptyHint?.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
        emptyHint?.bringToFront()
        Utils.log("ChatsSearchBar: query='$q' chats=${rows.size} hits=${hits.size}")
    }

    /** Every conversation the native adapter holds, in list order. */
    private fun collectChats(): List<ChatRow> {
        val rv = list ?: return emptyList()
        val adapter = rv.adapter ?: return emptyList()
        val getItem = runCatching {
            adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<ChatRow>(adapter.itemCount)
        for (i in 0 until adapter.itemCount) {
            val item = runCatching { getItem.invoke(adapter, i) }.getOrNull() ?: continue
            out.add(ChatRow.of(item) ?: continue)
        }
        return out
    }

    /** Open the tapped chat using the same intent shape the incoming-message notification uses. */
    private fun openChat(row: ChatRow) {
        runCatching {
            val ctx = Utils.application
            val intent = android.content.Intent().apply {
                setClassName(ctx, "com.tencent.qqnt.watch.mainframe.MainActivity")
                action = "com.tencent.qqnt.watch.action.MAINACTIVITY"
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra("open_chatfragment", true)
                putExtra("tab_index", 0)
                putExtra("entrance", 6)
                putExtra("key_peerId", row.peerUid)
                putExtra("key_peerUin", row.peerUin)
                putExtra("key_chat_type", row.chatType)
                putExtra("key_chat_name", row.name)
            }
            ctx.startActivity(intent)
            Utils.log("ChatsSearchBar: open chat peer=${row.peerUid} type=${row.chatType}")
        }.onFailure { Utils.log("ChatsSearchBar: open chat failed: $it") }
    }

    /** Show the keyboard for the search field (used when the user taps the field). */
    fun showKeyboard(v: View) {
        runCatching {
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager)
                .showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Drop any active query. Safe to call at any time. */
    fun clearQuery() {
        query = ""
        runCatching { field?.setText("") }
        runCatching { resultsAdapter?.submit(emptyList()) }
        runCatching { resultsLayerId?.visibility = View.GONE }
    }

    // ── one conversation ───────────────────────────────────────────────────────────────────────

    /** A conversation as read from the native adapter — only the fields the search needs. */
    class ChatRow(
        val name: String,
        val peerUid: String,
        val peerUin: Long,
        val chatType: Int,
    ) {
        fun matches(q: String): Boolean = name.contains(q, ignoreCase = true)

        companion object {
            /**
             * Build a row from a `RecentContactChatItem`. Its `a` field is the `RecentContactInfo`
             * (same access pattern as `ChatListLongClickMenu`/`有人@我`), read reflectively because
             * the class lives in the patched target APK with R8-minified accessors.
             */
            fun of(item: Any): ChatRow? = runCatching {
                val info = item.javaClass.getField("a").get(item) ?: return null
                val uid = runCatching { info.javaClass.getField("peerUid").get(info) as? String }
                    .getOrNull() ?: return null
                val remark = runCatching { info.javaClass.getField("remark").get(info) as? String }
                    .getOrNull()
                val peerName = runCatching { info.javaClass.getField("peerName").get(info) as? String }
                    .getOrNull()
                val uin = runCatching { info.javaClass.getField("peerUin").getLong(info) }
                    .getOrDefault(0L)
                val chatType = runCatching { info.javaClass.getField("chatType").getInt(info) }
                    .getOrDefault(0)
                val display = remark?.takeIf { it.isNotEmpty() } ?: peerName?.takeIf { it.isNotEmpty() }
                    ?: uid
                ChatRow(display, uid, uin, chatType)
            }.getOrNull()
        }
    }

    // ── result list ────────────────────────────────────────────────────────────────────────────

    private class ResultAdapter(
        val onClick: (ChatRow) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.Row>() {
        private val rows = ArrayList<ChatRow>()

        class Row(val view: LinearLayout, val name: TextView) : RecyclerView.ViewHolder(view)

        fun submit(list: List<ChatRow>) {
            rows.clear()
            rows.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val ctx = parent.context
            val name = TextView(ctx).apply {
                setTextColor(M3.onSurface)
                textSize = 13f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            }
            val view = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = M3.ripple(M3.rounded(M3.surfaceContainer, M3.radiusLg))
                addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            return Row(view, name)
        }

        override fun onBindViewHolder(holder: Row, position: Int) {
            val row = rows[position]
            holder.name.text = row.name
            holder.view.setOnClickListener { onClick(row) }
        }
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
