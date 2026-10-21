package momoi.mod.qqpro.hook.chatlist

import android.content.Context
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
 * Search box for the messages page (1st main page).
 *
 * ## Layout: a floating field that scrolls away with the list
 *
 * The bar is an OVERLAY pinned to the top-right of the page, on top of the conversation list, with
 * the list given top padding equal to the bar height. It is therefore not a permanent toolbar row
 * (that used to eat a full row of a watch screen forever): scrolling the list down translates the
 * bar up by the scrolled distance, so it leaves the screen after about one bar height and returns
 * the moment the list is back at the top. See [attachCollapse]. While a query is active the bar is
 * pinned again, because it holds the text being edited and the clear button.
 *
 * The native list itself is never re-parented, re-adaptered or re-rowed — only its top padding is
 * changed, and the original value is restored in [detach], so the page can never come back
 * mis-padded.
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

        val content = FrameLayout(ctx).apply {
            addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(resultsLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        // The bar OVERLAYS the list instead of owning its own row (it used to be a second row of a
        // vertical LinearLayout, i.e. permanently pinned at the top and always costing that height).
        // It scrolls away with the list instead — see [attachCollapse] — so the page looks like a
        // plain conversation list with a floating search field on it.
        val wrap = FrameLayout(ctx).apply {
            tag = TAG
            // The pager will assign its own LayoutParams when it attaches this; until then it must
            // still hold a valid instance (null would throw in setLayoutParams).
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        }
        field = bar.tag as? EditText
        barView = bar
        emptyHint = hint
        resultsLayerId = resultsLayer

        bindListWhenReady(wrap, 0)
        installBackHandler(wrap)
        Utils.log("ChatsSearchBar: wrapped host root (floating bar over list)")
        return wrap
    }

    // ── collapse-on-scroll ─────────────────────────────────────────────────────────────────────
    //
    // The search field must stop being a fixed toolbar without disturbing the native conversation
    // list. So the list is never re-parented, re-adaptered or re-rowed: we only (a) add top padding
    // equal to the bar height — plain empty scrollable space, the same thing the page already does
    // with bottom padding for the nav bar — and (b) translate the bar up by the accumulated scroll
    // distance, clamped to its own height. Because the padding is part of the scrolled content, the
    // bar visually rides the bottom edge of that space and leaves the screen after ~one bar height,
    // then comes straight back when the list is scrolled to the top again.
    //
    // Restoring is exact: [savedListPaddingTop] is the original value and is written back in
    // [detach], so leaving and re-entering the page can never leave the list mis-padded.

    private var barView: View? = null
    private var barHeight = 0
    private var collapseOffset = 0
    private var savedListPaddingTop = -1
    private var collapseList: RecyclerView? = null

    private val collapseScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            // While a query is open the bar is pinned (it holds the text being edited).
            if (query.isNotEmpty()) {
                collapseOffset = 0
            } else {
                collapseOffset = (collapseOffset + dy).coerceIn(0, barHeight.coerceAtLeast(0))
            }
            applyCollapse()
        }
    }

    /** Install the padding + scroll tracking once the native list has been found. */
    private fun attachCollapse(rv: RecyclerView) {
        val bar = barView ?: return
        if (collapseList === rv) return
        restoreListPadding()
        rv.removeOnScrollListener(collapseScrollListener)
        collapseList = rv
        bar.post {
            runCatching {
                // The page can be torn down between install() and this post (fast page switch):
                // never pad a list we no longer own.
                if (barView !== bar) return@post
                val h = bar.height
                if (h <= 0) {
                    Utils.log("ChatsSearchBar: bar height 0, collapse skipped")
                    return@post
                }
                barHeight = h
                savedListPaddingTop = rv.paddingTop
                // Only reserve empty scrollable space; clipToPadding is deliberately left alone —
                // MainNav manages it for the page (content draws behind the translucent nav bar)
                // and forcing it here would change that. Content passing UNDER the floating bar
                // while scrolling is expected: that is what makes it look attached to the list.
                rv.setPadding(rv.paddingLeft, savedListPaddingTop + h, rv.paddingRight, rv.paddingBottom)
                collapseOffset = 0
                rv.addOnScrollListener(collapseScrollListener)
                applyCollapse()
                Utils.log("ChatsSearchBar: collapse installed (barH=$h padTop=$savedListPaddingTop)")
            }.onFailure { Utils.log("ChatsSearchBar: attachCollapse failed: $it") }
        }
    }

    private fun applyCollapse() {
        val bar = barView ?: return
        if (barHeight <= 0) return
        if (query.isNotEmpty()) {
            bar.translationY = 0f
            if (bar.visibility != View.VISIBLE) bar.visibility = View.VISIBLE
            return
        }
        bar.translationY = -collapseOffset.toFloat()
        // Fully scrolled away: drop it so it can't swallow touches meant for the list. It comes
        // back as soon as the accumulated offset falls below its height (scroll back to the top).
        val gone = collapseOffset >= barHeight
        val want = if (gone) View.GONE else View.VISIBLE
        if (bar.visibility != want) bar.visibility = want
    }

    /** Put the conversation list's original top padding back (idempotent). */
    private fun restoreListPadding() {
        val rv = collapseList ?: return
        if (savedListPaddingTop >= 0) {
            runCatching {
                rv.setPadding(rv.paddingLeft, savedListPaddingTop, rv.paddingRight, rv.paddingBottom)
            }
        }
        runCatching { rv.removeOnScrollListener(collapseScrollListener) }
        collapseList = null
        savedListPaddingTop = -1
        collapseOffset = 0
    }

    /**
     * While a query is active, BACK must close the search instead of leaving the app.
     *
     * The bar is part of the messages page, so a back press with the search open was popping the
     * activity — the user expects it to just dismiss the search. Registered against the hosting
     * fragment's lifecycle, but only AFTER the view is attached: during `onCreateView` the
     * fragment's view lifecycle doesn't exist yet, so the first attempt is deferred to the wrap's
     * onAttachedToWindow (guarded so re-attaches don't stack callbacks).
     */
    private fun installBackHandler(wrap: View) {
        if (backHandlerInstalled) return
        if (wrap.isAttachedToWindow) {
            registerBackHandler(wrap)
            return
        }
        wrap.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                registerBackHandler(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun registerBackHandler(wrap: View) {
        if (backHandlerInstalled) return
        runCatching {
            val activity = wrap.context.findActivity() ?: return
            // Registered against the ACTIVITY (no LifecycleOwner needed): viewLifecycleOwner does
            // not exist during onCreateView, and ViewTreeLifecycleOwner isn't on the compile
            // classpath here. The callback is disabled whenever no search is open, so it never
            // swallows an ordinary back press, and it is torn down with the page in detach().
            val cb = object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    clearQuery()
                }
            }
            activity.onBackPressedDispatcher.addCallback(cb)
            backCallback = cb
            backHandlerInstalled = true
            Utils.log("ChatsSearchBar: back handler registered")
        }.onFailure { Utils.log("ChatsSearchBar: back handler failed: $it") }
    }

    private var backCallback: androidx.activity.OnBackPressedCallback? = null
    private var backHandlerInstalled = false

    private fun Context.findActivity(): androidx.activity.ComponentActivity? {
        var c: Context? = this
        while (c != null) {
            if (c is androidx.activity.ComponentActivity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    /** The overlay that hosts the (own) result list; null until installed. */
    private var resultsLayerId: View? = null

    private fun bindListWhenReady(wrap: View, attempt: Int) {
        val rv = findChatList(wrap)
        if (rv != null) {
            list = rv
            Utils.log("ChatsSearchBar: conversation list resolved (${rv.javaClass.simpleName})")
            attachCollapse(rv)
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
        // Drop the previous page's back callback: it holds the activity dispatcher, and a stale
        // one would keep intercepting back for a view that no longer exists.
        runCatching { backCallback?.remove() }
        backCallback = null
        backHandlerInstalled = false
        // Put the conversation list's original top padding back before dropping the reference, so
        // re-entering the page never inherits our collapse padding.
        restoreListPadding()
        field = null
        resultsAdapter = null
        emptyHint = null
        list = null
        resultsLayerId = null
        barView = null
        barHeight = 0
        collapseOffset = 0
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
            // Transparent, tight row: the field is a floating pill sitting ON the conversation list
            // rather than a toolbar strip above it. The old opaque full-width fill was what made it
            // read as a fixed bar even before it started scrolling away.
            setPadding(10.dp, 6.dp, 10.dp, 4.dp)
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
            // Bottom padding clears the main page's navigation bar, which is an overlay owned by the
            // main frame (not by this fragment): without it the last result sits under the nav.
            setPadding(0, 4.dp, 0, 56.dp)
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
        // Back is only "close the search" while a search is actually open; otherwise the callback
        // stays disabled and back keeps its normal meaning (leave the page / app).
        backCallback?.isEnabled = query.isNotEmpty()
        if (query.isEmpty()) {
            layer.visibility = View.GONE
            resultsAdapter?.submit(emptyList())
            emptyHint?.visibility = View.GONE
            // Give the list keyboard focus back to the conversation list's own state: just drop it.
            field?.clearFocus()
            applyCollapse()
            return
        }
        val rows = collectChats()
        val q = query
        val hits = rows.filter { it.matches(q) }
        // A live query pins the bar back to the top: the field holds the text being edited and the
        // clear button lives in it, so it must never be scrolled out of reach.
        applyCollapse()
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
        // Disable before clearing the text so the resulting empty-query runSearch can't leave a
        // disabled callback behind on the next real back press.
        backCallback?.isEnabled = false
        runCatching {
            val f = field
            if (f != null && f.text.isNotEmpty()) {
                f.setText("")
                f.clearFocus()
                runCatching {
                    (f.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(f.windowToken, 0)
                }
            }
        }
        runCatching { resultsAdapter?.submit(emptyList()) }
        runCatching { resultsLayerId?.visibility = View.GONE }
        runCatching { emptyHint?.visibility = View.GONE }
    }

    // ── one conversation ───────────────────────────────────────────────────────────────────────

    /** A conversation as read from the native adapter — only the fields the search needs. */
    class ChatRow(
        val name: String,
        val peerUid: String,
        val peerUin: Long,
        val chatType: Int,
        /** Last-message preview shown under the name (empty → hidden). */
        val preview: String = "",
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
                val digest = runCatching { info.javaClass.getField("msgSummary").get(info) as? String }
                    .getOrNull()
                val display = remark?.takeIf { it.isNotEmpty() } ?: peerName?.takeIf { it.isNotEmpty() }
                    ?: uid
                ChatRow(display, uid, uin, chatType, digest.orEmpty().trim())
            }.getOrNull()
        }
    }

    // ── result list ────────────────────────────────────────────────────────────────────────────

    private class ResultAdapter(
        val onClick: (ChatRow) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.Row>() {
        private val rows = ArrayList<ChatRow>()

        class Row(val view: View, val name: TextView, val preview: TextView) : RecyclerView.ViewHolder(view)

        fun submit(list: List<ChatRow>) {
            rows.clear()
            rows.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        /**
         * A result row, styled as an M3 list card: a rounded surfaceContainer card with a tonal
         * leading "chat" glyph, the chat name on the first line and the last-message preview
         * (dimmed, single line) below it. Cards are separated by vertical spacing rather than
         * dividers, which reads better on a small round screen.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val ctx = parent.context
            val glyph = android.widget.ImageView(ctx).apply {
                setImageDrawable(MaterialSymbol(MaterialSymbols.chat_bubble, M3.primary))
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            val name = TextView(ctx).apply {
                setTextColor(M3.onSurface)
                textSize = 13.5f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            }
            val preview = TextView(ctx).apply {
                setTextColor(M3.onSurfaceVariant)
                textSize = 11f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
            }
            val text = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(preview, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 2.dp })
            }
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp, 9.dp, 12.dp, 9.dp)
                background = M3.ripple(M3.rounded(M3.surfaceContainer, M3.radiusLg))
                addView(glyph, LinearLayout.LayoutParams(16.dp, 16.dp).apply { marginEnd = 10.dp })
                addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            // Outer margin wrapper: RecyclerView has no per-item spacing, and the card must not
            // stretch edge-to-edge (it would read as one solid block).
            val wrap = FrameLayout(ctx).apply {
                setPadding(10.dp, 3.dp, 10.dp, 3.dp)
                addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
            }
            return Row(wrap, name, preview)
        }

        override fun onBindViewHolder(holder: Row, position: Int) {
            val row = rows[position]
            holder.name.text = row.name
            if (row.preview.isNotEmpty()) {
                holder.preview.text = row.preview
                holder.preview.visibility = View.VISIBLE
            } else {
                holder.preview.visibility = View.GONE
            }
            holder.view.setOnClickListener { onClick(row) }
        }
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
