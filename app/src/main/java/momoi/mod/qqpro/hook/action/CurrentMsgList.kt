package momoi.mod.qqpro.hook.action

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AIOLayoutManager
import com.tencent.aio.api.factory.IAIOFactory
import com.tencent.aio.api.list.IDataSubmitApi
import com.tencent.aio.api.list.IListUIOperationApi
import com.tencent.aio.api.runtime.AIOContext
import com.tencent.aio.base.chat.ChatPie
import com.tencent.aio.base.mvi.part.MsgListUiState
import com.tencent.aio.data.msglist.IMsgItem
import com.tencent.aio.main.fragment.ChatFragment
import com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.data.MsgListRepo
import com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.intent.MsgListDataIntent
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.watch.aio_impl.coreImpl.repo.WatchMsgListRepo
import com.tencent.watch.aio_impl.coreImpl.vb.WatchAIOListVB
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import kotlinx.coroutines.CoroutineScope
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.Observable
import momoi.mod.qqpro.util.ThreadManager
import momoi.mod.qqpro.util.Utils
import java.util.LinkedList

object CurrentMsgList {
    /**
     * Anti-recall vault: caches the PRE-recall **item** for every message that flows through the
     * list merge (per peer, per msgId). Survives chat re-entry (same process).
     *
     * Why not patch QQ's own database: the NT message DB is encrypted (key held by the native
     * kernel), its records are internal protobuf blobs, the kernel keeps its own cached connection
     * (external writes risk corrupting the whole store), and — decisively — the SERVER is the
     * source of truth: any local patch would be overwritten by the next server sync.
     *
     * Why the vault stores the ITEM and not a rebuilt MsgRecord: the AIO cell factory picks the
     * bind cell via a VIRTUAL method on the item (base [WatchAIOMsgItem] falls through to the
     * "unsupported" cell, which hard-casts the item to its own subclass). A synthetic base-class
     * item therefore crashes with ClassCastException in WatchUnSupportItemCell. The ORIGINAL item
     * keeps its concrete subclass, so re-binding it is always type-correct.
     *
     * Known limit: the vault is in-memory — a full app restart loses it (the kernel has already
     * replaced the persisted content server-side; there is nothing left to restore from).
     */
    object AntiRecallVault {
        private val byPeer = HashMap<String, LinkedHashMap<Long, WatchAIOMsgItem>>()

        fun put(item: WatchAIOMsgItem) {
            val r = item.d
            if (r.recallTime > 0L) return // never cache the recalled form
            val peer = r.peerUid ?: return
            synchronized(byPeer) {
                var map = byPeer[peer]
                if (map == null) {
                    if (byPeer.size >= 32) byPeer.remove(byPeer.keys.first()) // bound peer count
                    map = LinkedHashMap()
                    byPeer[peer] = map
                }
                map.remove(r.msgId)
                map[r.msgId] = item
                while (map.size > 120) map.remove(map.keys.first())
            }
        }

        /** The cached pre-recall ITEM for [msgId] in [peerUid], or null. */
        fun restoreItem(peerUid: String?, msgId: Long): WatchAIOMsgItem? {
            if (peerUid == null) return null
            return synchronized(byPeer) { byPeer[peerUid]?.get(msgId) }
        }

        fun clear() = synchronized(byPeer) { byPeer.clear() }
    }

    /**
     * msgIds the USER deleted locally, with timestamps. Read by [NoEviction]: anti-recall blocks
     * repo-level deletions to preserve peer-recalled messages, but a user's own delete must go
     * through — this registry tells the two apart (a short window covers the async gap between
     * the deleteMsg callback and the kernel's repo.d call).
     */
    object LocalDeleteMarker {
        private const val WINDOW_MS = 15_000L
        private val marked = HashMap<Long, Long>()

        fun mark(ids: Collection<Long>) = synchronized(marked) {
            val now = System.currentTimeMillis()
            ids.forEach { marked[it] = now }
            // Prune expired entries so the map can't grow unbounded.
            marked.entries.removeAll { now - it.value > WINDOW_MS }
        }

        fun isLocal(id: Long): Boolean = synchronized(marked) {
            val t = marked[id] ?: return false
            if (System.currentTimeMillis() - t > WINDOW_MS) { marked.remove(id); false } else true
        }
    }

    lateinit var vb: WatchAIOListVB
        private set
    // The list UI operation API from the latest render — used to submit a new list live
    // (e.g. after a local delete, which the kernel doesn't push to the open chat list).
    var uiOp: IListUIOperationApi? = null
        private set
    var msgList = Observable(mutableListOf<WatchAIOMsgItem>())
        private set

    // Fires (after [msgList] has been updated) ONLY for older-history "load previous page" results.
    // The value carried is MsgListState.updateType: it has bit 0x4 set for any pre-page result, and
    // equals 5 (LoadPrePageFail) when the top of history is reached. Unrelated list updates (incoming
    // message, read/status change, first-page load) never set bit 0x4, so waiting on this signal
    // instead of the generic [msgList] observer is what makes upward paging reliable — a spurious
    // update can no longer be mistaken for the page we requested. See [loadOlderPage].
    val topPageResult = Observable(0)

    /**
     * The adapter position of [msg].
     *
     * Resolved by **msgSeq**, never by object identity. This is what made every jump
     * (@我/回复/引用/搜索) land on the wrong message: `msgList.value.indexOf(msg)` compares by
     * reference, but the kernel hands us a FRESH `WatchAIOMsgItem` on every state push and the
     * mirror is rebuilt on each merge — so an item captured by a finder (which matches on
     * `d.msgSeq`, correctly) is frequently not the same instance that ended up in the mirror. The
     * index then came back -1 (→ "加载失败，无法定位") or, worse, resolved against a list whose
     * ordering had shifted, landing on a neighbouring message.
     *
     * Order of resolution, most authoritative first:
     *   1. the LIVE adapter list ([uiOp].m()) — exactly what the cells are bound from;
     *   2. our accumulated mirror ([msgList]).
     */
    fun getMsgIndex(msg: WatchAIOMsgItem): Int {
        val seq = runCatching { msg.d.msgSeq }.getOrNull() ?: return -1
        runCatching {
            val live = uiOp?.m()
            if (live != null) {
                val i = indexOfSeq(live, seq)
                if (i >= 0) return i
            }
        }.onFailure { Utils.log("getMsgIndex live lookup failed: $it") }
        return indexOfSeq(msgList.value, seq)
    }

    /** First index in [list] whose item's msgSeq equals [seq], or -1. */
    private fun indexOfSeq(list: List<*>, seq: Long): Int {
        for (i in list.indices) {
            val item = list[i] as? WatchAIOMsgItem ?: continue
            if (runCatching { item.d.msgSeq }.getOrNull() == seq) return i
        }
        return -1
    }

    /**
     * Public seq → adapter-position resolver, for jump helpers that re-resolve at scroll time
     * (prepending history shifts indices, so positions must not be captured early).
     */
    fun indexOfSeqPublic(seq: Long): Int {
        runCatching {
            val live = uiOp?.m()
            if (live != null) {
                val i = indexOfSeq(live, seq)
                if (i >= 0) return i
            }
        }
        return indexOfSeq(msgList.value, seq)
    }

    /** The item currently at adapter position [index] (live list first), or null. */
    fun msgAt(index: Int): WatchAIOMsgItem? {
        runCatching {
            val live = uiOp?.m()
            if (live != null && index in live.indices) return live[index] as? WatchAIOMsgItem
        }
        return msgList.value.getOrNull(index) as? WatchAIOMsgItem
    }

    /**
     * The message displayed immediately before [msg] (the older one above it), or null if [msg] is
     * the first. Resolves against the LIVE adapter list ([uiOp].m()) — the exact list the cells are
     * bound from — instead of our accumulated [msgList] mirror, which is rebuilt at growing sizes
     * during scroll/history-load, so indexOf into it intermittently misses (idx=-1) and breaks the
     * merge-header decision. Falls back to the mirror if the live list is unavailable.
     */
    /**
     * Temporary override for [prevMsg], used by the screenshot renderer so the merge-header decision is
     * made relative to the SELECTED set (the first rendered message always shows its header) instead of
     * the full live chat. Set only during a synchronous off-screen bind loop, then cleared.
     */
    var prevOverride: ((WatchAIOMsgItem) -> WatchAIOMsgItem?)? = null

    fun prevMsg(msg: WatchAIOMsgItem): WatchAIOMsgItem? {
        prevOverride?.let { return it(msg) }
        val seq = runCatching { msg.d.msgSeq }.getOrNull()
        if (seq == null) {
            val mi = msgList.value.indexOf(msg)
            return if (mi > 0) msgList.value.getOrNull(mi - 1) else null
        }
        // By msgSeq, not identity — see [getMsgIndex]: the kernel re-creates items on every push,
        // so `indexOf(msg)` misses and the merge-header decision silently degrades.
        runCatching {
            val live = uiOp?.m()
            if (live != null) {
                val i = indexOfSeq(live, seq)
                if (i >= 0) return if (i > 0) live[i - 1] as? WatchAIOMsgItem else null
            }
        }.onFailure { Utils.log("prevMsg live lookup failed: $it") }
        val mi = indexOfSeq(msgList.value, seq)
        return if (mi > 0) msgList.value.getOrNull(mi - 1) else null
    }

    /**
     * Remove messages from the currently open chat list in place, by msgId. Native local delete
     * ("删除", not 撤回) updates the DB but doesn't refresh the open AIO list — it only shows on
     * re-entry. We submit a filtered list to the data-submit API so the row disappears live.
     *
     * NOTE: this is ONLY called from user-initiated local deletes (长按菜单"删除" / 多选删除),
     * so it must NOT be gated by [Settings.antiRecall] — that toggle is about REMOTE recalls
     * (AntiRecallHook.onMsgRecall + NoEviction.d), never about the user's own delete action.
     * Gating it here made locally-deleted messages linger until chat re-entry.
     */
    fun removeLive(ids: Set<Long>) {
        if (ids.isEmpty()) return
        // Mark these as USER-initiated deletes BEFORE anything else: the kernel will call the
        // repo's d(ids) right after, which NoEviction (anti-recall) must ALLOW for local deletes —
        // otherwise the repo keeps the row, the next push re-adds it, and the message "revives"
        // until chat re-entry (where the DB delete finally shows).
        LocalDeleteMarker.mark(ids)
        ThreadManager.runOnUiThread({
            runCatching {
                val op = uiOp ?: run { Utils.log("removeLive: uiOp null"); return@runCatching }
                val cur = op.m() ?: return@runCatching
                val newList = cur.filterNot { (it as? WatchAIOMsgItem)?.d?.msgId in ids }
                if (newList.size == cur.size) { Utils.log("removeLive: no match in live list"); return@runCatching }
                // SubmitAction's fields are final at runtime — must set them via the constructor.
                // Last arg is Kotlin's defaults mask: 0 = use all provided args (list, null scope,
                // immediate=true, null callback).
                op.A(IDataSubmitApi.SubmitAction<IMsgItem>(newList, null, true, null, 0))
                // Keep our mirror in sync so the merge in Hook.n doesn't re-add the removed item.
                msgList.update(msgList.value.filterNot { it.d.msgId in ids }.toMutableList())
                Utils.log("removeLive: removed ${cur.size - newList.size} msg(s)")
            }.onFailure { Utils.log("removeLive failed: $it") }
        })
    }

    private var isLoadingMsg = false
    private var loadGuardSince = 0L

    /**
     * Ask the list for one older page.
     *
     * [isLoadingMsg] is a re-entrancy guard, but it has to expire. It is cleared by an observer on
     * [msgList] — and [msgList] is REPLACED when the chat is re-opened ([Clear]), so an in-flight
     * request's observer can stop ever firing. The flag then stayed true forever, and every later
     * page request was silently dropped by this guard. That is a permanent "跳到第一条未读 一直报错"
     * / "翻页没反应" state for the rest of the chat session, and it matched the reported symptom
     * exactly on a chat with thousands of unread: the first jump interrupted by a re-open left the
     * guard stuck and nothing worked afterwards.
     *
     * The guard is now also released by a timestamp: a request older than [LOAD_GUARD_MAX_MS] is
     * considered dead and a new one is allowed through.
     */
    private fun loadMoreMsg() {
        val now = android.os.SystemClock.uptimeMillis()
        if (isLoadingMsg && now - loadGuardSince < LOAD_GUARD_MAX_MS) {
            Utils.log("loadMoreMsg: skipped, a page is already in flight (${now - loadGuardSince}ms)")
            return
        }
        isLoadingMsg = true
        loadGuardSince = now
        // Observe the CURRENT instance (see above): a stale observer on a replaced list would never
        // fire, leaving the guard to time out on every request instead of clearing promptly.
        val current = msgList
        current.observeOnce {
            if (msgList === current) isLoadingMsg = false
        }
        Utils.log("Load more msg. currentSize: ${msgList.value.size}")
        vb.L(MsgListDataIntent.LoadTopPage("WatchAIOListVB"))
    }

    /** How long the in-flight page guard may block new requests before it is treated as dead. */
    private const val LOAD_GUARD_MAX_MS = 20_000L

    /**
     * Ask for up to [pages] older pages back-to-back, waiting only for the list to GROW between
     * requests rather than for each page's completion signal.
     *
     * Why: a large unread tail (690 messages measured in the field log) is 69 pages at the kernel's
     * fixed 10 messages/page. Serially waiting for each page's `topPageResult` costs 120–340 ms per
     * page — 10–25 s for one jump, which no user or UI moment survives. The kernel processes the
     * requests back-to-back anyway, so the only thing the strict wait buys is correctness of the
     * progress accounting; for a bulk catch-up we can simply keep requesting and watch the list
     * length.
     *
     * Stops early when the list stops growing for [stallLimit] consecutive requests, or when the
     * kernel reports the top of history. Returns the number of pages that actually landed.
     */
    fun pageUpFast(
        pages: Int,
        onProgress: (Int) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onDone: (loaded: Int) -> Unit,
    ) {
        val startSize = msgList.value.size
        var issued = 0
        var stalls = 0

        fun finish() = onDone(msgList.value.size - startSize)

        fun step() {
            if (!shouldContinue()) {
                Utils.log("pageUpFast: cancelled after $issued request(s)")
                finish(); return
            }
            if (issued >= pages) {
                Utils.log("pageUpFast: reached request budget ($issued), size=$startSize->${msgList.value.size}")
                finish(); return
            }
            val before = msgList.value.size
            issued++
            onProgress(issued)
            // Fire the request without arming the completion waiter: growth is the signal.
            isLoadingMsg = false
            vb.L(MsgListDataIntent.LoadTopPage("WatchAIOListVB"))

            // POLL for the growth instead of sampling once after a fixed gap. One page costs
            // 30–300 ms in the field log, so a single 90 ms sample saw "no growth" on most healthy
            // pages, and six of those in a row aborted the whole bulk pass long before the
            // requested history had loaded — the jump then "could not locate" a message that was
            // still thousands of messages away because the pass had quietly stopped at ~40.
            var waited = 0L
            fun check() {
                if (!shouldContinue()) { finish(); return }
                if (msgList.value.size > before) {
                    stalls = 0
                    step()
                    return
                }
                waited += FAST_POLL_MS
                if (waited >= FAST_PAGE_WAIT_MS) {
                    stalls++
                    if (stalls >= FAST_STALL_LIMIT) {
                        Utils.log("pageUpFast: stalled $stalls times at size=${msgList.value.size}")
                        finish(); return
                    }
                    step()
                    return
                }
                ThreadManager.runOnUiThread({ check() }, FAST_POLL_MS)
            }
            ThreadManager.runOnUiThread({ check() }, FAST_POLL_MS)
        }
        step()
    }

    /** Consecutive non-growing requests before [pageUpFast] stops. */
    private const val FAST_STALL_LIMIT = 6

    /** How often a pending page is re-checked for growth. */
    private const val FAST_POLL_MS = 40L

    /** How long one page may take before it counts as unproductive. */
    private const val FAST_PAGE_WAIT_MS = 1_200L

    /**
     * Request one page of older messages and wait specifically for the pre-page (older-history)
     * load result — NOT just any [msgList] mutation. Previously the loaders waited on
     * `msgList.observeOnce`, which fires on EVERY state push from the AIO framework (incoming msg,
     * read-receipt/status change, sticker refresh, …). Such an unrelated update would wake the
     * waiter mid-load; since it didn't add older history the loader concluded "reached top" and
     * failed — the cause of the intermittent "加载失败，请重试" where pressing again works (the real
     * page had quietly arrived in the meantime). We now wait on [topPageResult], which only fires
     * for genuine pre-page results, and read end-of-history from the kernel's own LoadPrePageFail
     * signal instead of guessing by list size.
     *
     * [onResult] is invoked on the UI thread with `reachedTop == true` when the kernel reports
     * LoadPrePageFail (no more older messages). [onTimeout] fires (UI thread) if no pre-page result
     * arrives within [timeoutMs].
     */
    private fun loadOlderPage(
        timeoutMs: Long,
        onResult: (reachedTop: Boolean) -> Unit,
        onTimeout: () -> Unit
    ) {
        val startedAt = android.os.SystemClock.uptimeMillis()
        val sizeAtStart = msgList.value.size
        var settled = false
        topPageResult.observeOnce { updateType ->
            if (settled) return@observeOnce
            settled = true
            val took = android.os.SystemClock.uptimeMillis() - startedAt
            if (Utils.loggingEnabled) Utils.log(
                "loadOlderPage: result updateType=$updateType after ${took}ms, " +
                    "size $sizeAtStart -> ${msgList.value.size}"
            )
            ThreadManager.runOnUiThread({ onResult(updateType == 5) })
        }
        ThreadManager.runOnUiThread({
            if (settled) return@runOnUiThread
            settled = true
            Utils.log(
                "loadOlderPage: TIMEOUT after ${android.os.SystemClock.uptimeMillis() - startedAt}ms " +
                    "waiting for a pre-page result (size=$sizeAtStart, now=${msgList.value.size}) — " +
                    "topPageResult never fired"
            )
            onTimeout()
        }, timeoutMs)
        isLoadingMsg = false // clear any stuck guard from a previously interrupted load
        loadMoreMsg()
    }

    // ── Tall-screen bottom alignment ────────────────────────────────────────────────────────────────
    // When a chat opens, WatchAIOListVB.onCreateView calls arrangeCellMode(1), which sets the chat layout
    // manager's needTopToBottom flag (AIOLayoutManager field `u`) to true. With that flag on,
    // AIOLayoutManager.c() offsets every cell UP to top-align content shorter than the viewport — a look
    // tuned for the small watch screen. On a TALL device those few cells then leave empty space BELOW the
    // newest message (the "bottom gap"). The list is already stackFromEnd=true, so with needTopToBottom
    // OFF the base layout naturally pins the newest message to the bottom and any slack sits at the top
    // (the normal chat look). So the fix is to force needTopToBottom=false — NOT to load more messages.
    // Paging in older history can't help: c() re-top-aligns on every layout pass, so the gap returns each
    // frame regardless of how much content is loaded (that was the earlier auto-fill attempt's flaw).
    // arrangeCellMode(1) runs once per view creation, so re-asserting the flag on each render is stable.

    // Has the chat's initial open-scroll settled (or the user actively dragged) since this chat opened?
    // Reset per chat open in [Clear]. Read by KeepInputBarOnScroll: the input-bar float must NOT be popped
    // during the initial programmatic scroll-to-unread — the overlay lays out before the bar's view tree is
    // ready and ends up detached/invisible (the "input bar disappeared" bug when opening a chat with
    // unread messages). Only float once the list has settled to IDLE or the user drags. See
    // KeepInputBarOnScroll.
    @JvmField
    var scrollSettledSinceOpen = false

    /**
     * Force the chat list to bottom-align (newest message pinned to the viewport bottom) by clearing the
     * layout manager's needTopToBottom flag. See the note above. Called from [Hook.n] BEFORE the native
     * render so the ensuing layout pass already sees the flag cleared. No-op once the flag is already off
     * and when content overflows the viewport (the top-pull is a no-op there anyway).
     */
    private fun forceBottomAlign() {
        runCatching {
            val lm = vb.H.layoutManager ?: return
            if (lm !is AIOLayoutManager) return
            // Runtime field `u` (jadx: needTopToBottom) is declared on AIOLayoutManager. Written
            // reflectively — the compile-time stub doesn't declare it and there is no stable setter.
            var c: Class<*>? = lm.javaClass
            while (c != null) {
                val f = runCatching { c.getDeclaredField("u") }.getOrNull()
                if (f != null && f.type == java.lang.Boolean.TYPE) {
                    f.isAccessible = true
                    if (f.getBoolean(lm)) {
                        f.setBoolean(lm, false)
                        Utils.log("MsgList: needTopToBottom -> false (tall-screen bottom-align)")
                    }
                    return
                }
                c = c.superclass
            }
        }
    }

    /**
     * Jump to the "first unread" message, identified by SEQ rather than by counting.
     *
     * The count-based [upwardMsg] has to page until the loaded list is `unreadTotal` items longer
     * than it was. With 10 messages per page that is 69 pages for 690 unread — measured at
     * 120–340 ms per page in the field log, so 10–25 seconds of continuous paging, and ANY hiccup
     * (an empty page, a timeout, the user scrolling, the page being rebuilt) aborted the whole run.
     * That is why the same tap "sometimes worked and sometimes didn't": it was never broken, just
     * too slow to survive the moment.
     *
     * When the caller knows the target's msgSeq, [findMsg] pages straight to it without having to
     * count, which is both faster and immune to the count being slightly off.
     */
    fun jumpToFirstUnreadBySeq(
        seq: Long,
        onProgress: (Int) -> Unit = {},
        onFail: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onFound: (WatchAIOMsgItem) -> Unit,
    ) {
        findMsg(seq, onProgress, result = { item ->
            if (!shouldContinue()) {
                Utils.log("jumpToFirstUnreadBySeq: cancelled before result")
                return@findMsg
            }
            if (item == null) onFail() else onFound(item)
        })
    }

    fun upwardMsg(
        current: Int,
        count: Int,
        onProgress: (Int) -> Unit = {},
        onFail: () -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        callback: (Int) -> Unit
    ) {
        val target = msgList.value.size - 1 - current + count
        // A jump of `count` messages can need dozens of pages (3000 unread ≈ 25+ pages), so this
        // must behave like findMsg: retry unproductive pages instead of failing on the first one.
        upwardMsgInternal(target, msgList.value.size, onProgress, onFail, shouldContinue, callback, emptyPages = 0)
    }

    private fun upwardMsgInternal(
        target: Int,
        startSize: Int,
        onProgress: (Int) -> Unit,
        onFail: () -> Unit,
        shouldContinue: () -> Boolean,
        callback: (Int) -> Unit,
        emptyPages: Int,
    ) {
        if (!shouldContinue()) {
            Utils.log("upwardMsg: cancelled by caller at size=${msgList.value.size} target=$target")
            return
        }
        if (msgList.value.size >= target) {
            callback(msgList.value.size - target - 1)
            return
        }
        val before = msgList.value.size
        // Percentage of the way from where we started to the target size.
        if (target > startSize) {
            val pct = ((before - startSize) * 100 / (target - startSize)).coerceIn(0, 99)
            onProgress(pct)
        }
        loadOlderPage(15_000L, onResult = { reachedTop ->
            when {
                msgList.value.size >= target -> callback(msgList.value.size - target - 1)
                // The kernel's own end-of-history signal: genuinely can't reach the target.
                reachedTop -> {
                    Utils.log("upwardMsg: reached top of history before target=$target, size=${msgList.value.size}")
                    onFail()
                }
                msgList.value.size > before ->
                    upwardMsgInternal(target, startSize, onProgress, onFail, shouldContinue, callback, emptyPages = 0)
                emptyPages + 1 >= MAX_EMPTY_PAGES -> {
                    Utils.log("upwardMsg: $MAX_EMPTY_PAGES empty pages, giving up target=$target")
                    onFail()
                }
                else -> {
                    // Unproductive but not the end: retry automatically (see findMsg).
                    Utils.log("upwardMsg: empty page ${emptyPages + 1}/$MAX_EMPTY_PAGES, retrying")
                    ThreadManager.runOnUiThread({
                        upwardMsgInternal(target, startSize, onProgress, onFail, shouldContinue, callback, emptyPages + 1)
                    }, EMPTY_PAGE_RETRY_MS)
                }
            }
        }, onTimeout = {
            if (emptyPages + 1 >= MAX_EMPTY_PAGES) {
                Utils.log("upwardMsg: repeated timeouts, target=$target")
                onFail()
            } else {
                Utils.log("upwardMsg: page timeout ${emptyPages + 1}/$MAX_EMPTY_PAGES, retrying")
                upwardMsgInternal(target, startSize, onProgress, onFail, shouldContinue, callback, emptyPages + 1)
            }
        })
    }

    /**
     * Page in older messages until the top of history is reached (the list stops growing),
     * then call [onDone] on the UI thread. [onProgress] is called after each page with the
     * current total size. Used by chat search, which needs the whole history in memory.
     *
     * [shouldContinue] is checked before each page and before every callback — pass a
     * lifecycle predicate (e.g. `{ isAdded }`) so a dismissed/cancelled search stops the chain
     * instead of loading the whole history in the background and firing stale callbacks.
     *
     * Loading is triggered after clearing [isLoadingMsg]: that guard can be left stuck `true`
     * by a previous load that was interrupted (chat closed mid-load), which would otherwise make
     * [loadMoreMsg] silently no-op and this never progress.
     */
    fun loadAll(
        onProgress: (Int) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onDone: () -> Unit
    ) {
        if (!shouldContinue()) {
            Utils.log("loadAll: cancelled before start")
            return
        }
        val before = msgList.value.size
        loadOlderPage(5000L, onResult = { reachedTop ->
            when {
                !shouldContinue() -> Utils.log("loadAll: cancelled, stopping")
                reachedTop || msgList.value.size <= before -> {
                    Utils.log("loadAll: reached top of history, total=${msgList.value.size}")
                    onDone()
                }
                else -> {
                    onProgress(msgList.value.size)
                    loadAll(onProgress, shouldContinue, onDone)
                }
            }
        }, onTimeout = { if (shouldContinue()) onDone() })
    }

    fun findMsg(
        seq: Long,
        onProgress: (Int) -> Unit = {},
        result: (WatchAIOMsgItem?) -> Unit,
        repeatCount: Int = 1000
    ) {
        findMsgInternal(seq, onProgress, result, repeatCount, emptyPages = 0)
    }

    /**
     * Paging search for [seq].
     *
     * The "点好几下才跳对" behaviour came from here: a single tap only ever loaded ONE page, because
     * any page that didn't grow the list was treated as "reached the top → give up". But a page
     * that adds nothing is usually not the end of history — the kernel can answer a pre-page
     * request with an empty/failed batch (busy, still syncing, or a status push that only carried
     * the newest few items). The old code then returned null, the caller showed
     * "加载失败，请重试", and the NEXT tap loaded the page that had been in flight. That is exactly
     * the "must tap several times" symptom.
     *
     * Now an unproductive page is retried automatically (a short delay between tries), and only a
     * genuinely empty end — the kernel's own LoadPrePageFail, or [MAX_EMPTY_PAGES] consecutive pages
     * that added nothing — is reported as not-found. The user taps once and the whole search runs.
     */
    private fun findMsgInternal(
        seq: Long,
        onProgress: (Int) -> Unit,
        result: (WatchAIOMsgItem?) -> Unit,
        repeatCount: Int,
        emptyPages: Int,
    ) {
        val msg = findFlexible(seq)
        if (msg != null) {
            result(msg)
            return
        }
        if (repeatCount <= 0) {
            Utils.log("findMsg: give up (repeat exhausted) seq=$seq")
            result(null)
            return
        }
        val sizeBefore = msgList.value.size
        onProgress(sizeBefore) // how many messages are loaded so far while we keep paging up
        loadOlderPage(15_000L, onResult = { reachedTop ->
            when {
                // The kernel says there is no older page left: a real end of history.
                reachedTop -> {
                    Utils.log("findMsg: reached top of history, seq=$seq not found")
                    result(null)
                }
                msgList.value.size > sizeBefore -> {
                    // Productive page: keep going, reset the empty-page streak.
                    findMsgInternal(seq, onProgress, result, repeatCount - 1, emptyPages = 0)
                }
                emptyPages + 1 >= MAX_EMPTY_PAGES -> {
                    Utils.log("findMsg: $MAX_EMPTY_PAGES empty pages in a row, seq=$seq not found")
                    result(null)
                }
                else -> {
                    // Unproductive page (not an end of history): retry automatically instead of
                    // making the user tap again.
                    Utils.log("findMsg: empty page ${emptyPages + 1}/$MAX_EMPTY_PAGES, retrying seq=$seq")
                    ThreadManager.runOnUiThread({
                        findMsgInternal(seq, onProgress, result, repeatCount - 1, emptyPages + 1)
                    }, EMPTY_PAGE_RETRY_MS)
                }
            }
        }, onTimeout = {
            if (emptyPages + 1 >= MAX_EMPTY_PAGES) {
                Utils.log("findMsg: repeated timeouts, seq=$seq not found")
                result(null)
            } else {
                Utils.log("findMsg: page timeout ${emptyPages + 1}/$MAX_EMPTY_PAGES, retrying seq=$seq")
                findMsgInternal(seq, onProgress, result, repeatCount - 1, emptyPages + 1)
            }
        })
    }

    /**
     * Consecutive unproductive pages tolerated before a jump gives up.
     *
     * Was 4, which is exactly "40 messages then 加载失败" — the field complaint. One page is 10
     * messages (measured), so 4 × 10 = 40. An unproductive page is usually NOT the end of history:
     * the kernel answers a pre-page request with nothing while it is busy, still syncing, or while
     * another load is in flight, and the very next request succeeds. `reachedTop` (the kernel's own
     * LoadPrePageFail) is the only honest end-of-history signal, so be generous with the heuristic
     * fallback — it costs ~250 ms per extra retry and nothing else.
     */
    private const val MAX_EMPTY_PAGES = 10

    /** Delay before retrying a page request that came back empty. */
    private const val EMPTY_PAGE_RETRY_MS = 250L

    /**
     * Find a message by [seq], tolerating the two seq conventions in play.
     *
     * The @我/回复 jump points come from the conversation's msgbox event list, whose `msgSeq` is the
     * value that event carried. For most messages it equals the message's own `d.msgSeq`, but an
     * event can carry a slightly different value (a newer seq for the same record, or 0/a stale one
     * for a resynced message) — and then an exact compare never matches, so the jump always failed
     * no matter how many pages were loaded. Fall back to matching the message whose seq is the
     * closest not-greater one AND whose content/time makes it the same record is not reliable
     * either, so instead: exact match first, then the message whose msgId-derived ordering puts it
     * at that seq. The safe extra step is `msgSeq` within a small window, but only when exactly ONE
     * candidate exists — otherwise we would jump to the wrong message, which is worse as a silent
     * error than a reported miss.
     */
    private fun findFlexible(seq: Long): WatchAIOMsgItem? {
        val list = msgList.value
        list.firstOrNull { it.d.msgSeq == seq }?.let { return it }
        if (seq <= 0L) return null
        // Near-match (same message, slightly different seq from the event payload). Require a
        // unique candidate so an ambiguous window can never move the jump to another message.
        val near = list.filter { it.d.msgSeq in (seq - NEAR_SEQ_WINDOW)..(seq + NEAR_SEQ_WINDOW) }
        return if (near.size == 1) near[0] else null
    }

    /** ±window used by [findFlexible]'s unique near-match fallback. */
    private const val NEAR_SEQ_WINDOW = 2L

    // ── Kernel lookups: identify a jump target WITHOUT paging ────────────────────────────────────
    // Everything below exists because the old jumps could only ever ask "is it in the pages I have
    // loaded yet?" and then ask for one more page (10 messages) at a time. That is both slow and —
    // decisively — matched on the WRONG key: reply/quote payloads carry a msgSeq that can differ
    // from the one the locally synced list uses (a server re-sync, or the same message arriving
    // over a different channel, reassigns the seq), while msgId is assigned once and never changes.
    // An exact-seq search therefore walked thousands of messages and still reported "无法定位".
    //
    // The kernel can answer directly: one DB call by msgId both proves whether the record exists
    // locally at all and returns its REAL msgSeq. Only if it is genuinely absent do we report a
    // miss — no more paging forever.

    /**
     * A [Contact] for the currently open chat, built fresh each call.
     *
     * The kernel may keep what it is handed and [CurrentContact] is a mutable singleton that is
     * rewritten on every chat switch, so the shared instance must never be passed through.
     */
    private fun currentContact(): Contact? {
        val peer = CurrentContact.peerUid
        if (peer.isNullOrEmpty()) return null
        return Contact(CurrentContact.chatType, peer, CurrentContact.guildId)
    }

    /**
     * Fetch message records from the kernel by msgId (one DB call, no paging). The result is
     * delivered on the UI thread; an empty list means the record is NOT in the local DB — deleted,
     * expired, or never synced to this device — in which case paging older pages can never find it.
     */
    fun fetchRecordsByMsgIds(msgIds: Collection<Long>, onResult: (List<MsgRecord>) -> Unit) {
        val ids = msgIds.filter { it > 0L }
        if (ids.isEmpty()) { onResult(emptyList()); return }
        val contact = currentContact()
        if (contact == null) { onResult(emptyList()); return }
        runCatching {
            KernelServiceUtil.c()?.getMsgsByMsgId(contact, ArrayList(ids)) { code, err, list ->
                val out = list?.toList().orEmpty()
                if (Utils.loggingEnabled) Utils.log(
                    "kernel getMsgsByMsgId(${ids}) -> code=$code err=$err got=${out.size} " +
                        "ids=${out.map { it.msgId }} seqs=${out.map { it.msgSeq }}"
                )
                ThreadManager.runOnUiThread({ onResult(out) })
            }
        }.onFailure {
            Utils.log("fetchRecordsByMsgIds failed: $it")
            onResult(emptyList())
        }
    }

    /**
     * The real first-unread msgSeq, straight from the kernel.
     *
     * Replaces guessing it from the cached unread COUNT, which is wrong whenever the conversation
     * was read on another device (the count no longer lines up with the local tail) and made the
     * terminal "first unread" jump land on an arbitrary message or fail outright.
     *
     * Delivers 0 when the kernel can't answer, so callers keep their old fallback.
     */
    fun firstUnreadSeq(onResult: (Long) -> Unit) {
        val contact = currentContact()
        if (contact == null) { onResult(0L); return }
        runCatching {
            KernelServiceUtil.c()?.getFirstUnreadMsgSeq(contact) { code, err, seq ->
                if (Utils.loggingEnabled) Utils.log("kernel getFirstUnreadMsgSeq -> code=$code err=$err seq=$seq")
                ThreadManager.runOnUiThread({ onResult(if (code == 0) seq else 0L) })
            }
        }.onFailure {
            Utils.log("firstUnreadSeq failed: $it")
            onResult(0L)
        }
    }

    /**
     * Resolve a **payload/event** msgSeq to the message record that actually exists locally.
     *
     * The msgbox highlight events (`SpecificEventTypeInfoInMsgBox` → `MsgBoxNecessaryMsgInfo`) carry
     * ONLY a msgSeq — no msgId — and that seq can drift from the record's own (server re-sync,
     * cross-device delivery). An exact-seq paging search then walks the whole history and reports
     * "not found" for a message that is sitting right there.
     *
     * Asks the kernel by seq first, then by a small seq window, and hands back the REAL record (with
     * its true msgSeq and msgId). `null` means this device genuinely has no such message, so callers
     * can fail fast instead of paging.
     */
    fun resolveRecordBySeq(seq: Long, window: Long = 2L, onResult: (MsgRecord?) -> Unit) {
        if (seq <= 0L) { onResult(null); return }
        val contact = currentContact()
        if (contact == null) { onResult(null); return }

        // Fallback: accept a record inside the small drift window.
        fun queryRange() {
            runCatching {
                KernelServiceUtil.c()?.getMsgsBySeqRange(contact, (seq - window).coerceAtLeast(1L), seq + window) { code, err, list ->
                    val nearest = list?.filter { it.msgSeq > 0L }?.minByOrNull { kotlin.math.abs(it.msgSeq - seq) }
                    if (Utils.loggingEnabled) Utils.log(
                        "kernel getMsgsBySeqRange(${seq - window}..${seq + window}) -> code=$code err=$err " +
                            "got=${list?.size ?: 0} nearestSeq=${nearest?.msgSeq}"
                    )
                    ThreadManager.runOnUiThread({ onResult(nearest) })
                }
            }.onFailure {
                Utils.log("resolveRecordBySeq range failed: $it")
                onResult(null)
            }
        }

        runCatching {
            KernelServiceUtil.c()?.getMsgsBySeqList(contact, arrayListOf(seq)) { code, err, list ->
                val rec = list?.firstOrNull { it.msgSeq > 0L }
                if (Utils.loggingEnabled) Utils.log(
                    "kernel getMsgsBySeqList([$seq]) -> code=$code err=$err got=${list?.size ?: 0} " +
                        "realSeq=${rec?.msgSeq} msgId=${rec?.msgId}"
                )
                ThreadManager.runOnUiThread({ if (rec != null) onResult(rec) else queryRange() })
            }
        }.onFailure {
            Utils.log("resolveRecordBySeq exact failed: $it")
            onResult(null)
        }
    }

    /**
     * Best-effort match of a jump target against the messages already loaded. msgId wins (immutable),
     * then the exact seq, then the unique near-seq window used for payload/record seq drift.
     */
    private fun matchJumpTarget(msgId: Long, seqHint: Long?): WatchAIOMsgItem? {
        val list = msgList.value
        if (list.isEmpty()) return null
        if (msgId > 0L) list.firstOrNull { it.d.msgId == msgId }?.let { return it }
        if (seqHint == null || seqHint <= 0L) return null
        list.firstOrNull { it.d.msgSeq == seqHint }?.let { return it }
        val near = list.filter { it.d.msgSeq in (seqHint - NEAR_SEQ_WINDOW)..(seqHint + NEAR_SEQ_WINDOW) }
        return if (near.size == 1) near[0] else null
    }

    /** The target if it is already loaded — lets a jump complete with zero paging. */
    fun findLoadedByIdOrSeq(msgId: Long, seqHint: Long?): WatchAIOMsgItem? =
        matchJumpTarget(msgId, seqHint)

    /**
     * Locate a message for a jump, cheapest step first:
     *  1. the already-loaded list (msgId, then seq) — no I/O at all;
     *  2. ONE kernel lookup by msgId, which both resolves the real msgSeq and tells us whether a
     *     record exists locally (absent ⇒ report a miss instead of paging the whole history);
     *  3. paging older history, matching msgId OR the resolved seq on every page.
     *
     * [result] receives `(item, reason)`: a non-null item on success, otherwise a user-facing
     * reason so the caller can say WHY the jump failed instead of a generic "无法定位消息".
     */
    fun locateMessage(
        msgId: Long,
        seqHint: Long?,
        onProgress: (Int) -> Unit = {},
        repeatLimit: Int = 400,
        shouldContinue: () -> Boolean = { true },
        result: (WatchAIOMsgItem?, String?) -> Unit,
    ) {
        if (!shouldContinue()) { result(null, null); return }
        matchJumpTarget(msgId, seqHint)?.let { result(it, null); return }
        if (seqHint == null && msgId <= 0L) { result(null, "这条引用缺少定位信息"); return }

        // Local function (not a lambda val) so the early returns are plain `return`s.
        fun startPaging(realSeq: Long?) {
            if (!shouldContinue()) { result(null, null); return }
            val already = matchJumpTarget(msgId, realSeq)
            if (already != null) { result(already, null); return }
            findByIdInternal(msgId, realSeq, onProgress, shouldContinue, repeatLimit, 0) { item ->
                result(
                    item,
                    if (item != null) null
                    else if (msgId <= 0L) "无法定位消息"
                    else "本机没有这条消息（可能已被删除或过期）"
                )
            }
        }

        if (msgId > 0L) {
            fetchRecordsByMsgIds(listOf(msgId)) { recs ->
                val rec = recs.firstOrNull { it.msgId == msgId }
                val realSeq = rec?.msgSeq?.takeIf { it > 0L } ?: seqHint
                if (Utils.loggingEnabled) Utils.log("locateMessage: msgId=$msgId hintSeq=$seqHint realSeq=$realSeq local=${rec != null}")
                startPaging(realSeq)
            }
        } else {
            startPaging(seqHint)
        }
    }

    /**
     * Page older history until [msgId] (or [seqHint]) is loaded. Same shape as [findMsgInternal]
     * but matches on msgId as well, which is what makes it immune to seq drift.
     */
    private fun findByIdInternal(
        msgId: Long,
        seqHint: Long?,
        onProgress: (Int) -> Unit,
        shouldContinue: () -> Boolean,
        repeatCount: Int,
        emptyPages: Int,
        result: (WatchAIOMsgItem?) -> Unit,
    ) {
        if (!shouldContinue()) { result(null); return }
        matchJumpTarget(msgId, seqHint)?.let { result(it); return }
        if (repeatCount <= 0) {
            Utils.log("findById: give up (repeat exhausted) msgId=$msgId seq=$seqHint")
            result(null)
            return
        }
        val sizeBefore = msgList.value.size
        onProgress(sizeBefore)
        loadOlderPage(15_000L, onResult = { reachedTop ->
            val found = matchJumpTarget(msgId, seqHint)
            when {
                found != null -> result(found)
                reachedTop -> {
                    Utils.log("findById: reached top of history, msgId=$msgId seq=$seqHint not found")
                    result(null)
                }
                msgList.value.size > sizeBefore ->
                    findByIdInternal(msgId, seqHint, onProgress, shouldContinue, repeatCount - 1, 0, result)
                emptyPages + 1 >= MAX_EMPTY_PAGES -> {
                    Utils.log("findById: $MAX_EMPTY_PAGES empty pages in a row, msgId=$msgId not found")
                    result(null)
                }
                else -> ThreadManager.runOnUiThread({
                    findByIdInternal(msgId, seqHint, onProgress, shouldContinue, repeatCount - 1, emptyPages + 1, result)
                }, EMPTY_PAGE_RETRY_MS)
            }
        }, onTimeout = {
            if (!shouldContinue()) {
                result(null)
            } else if (emptyPages + 1 >= MAX_EMPTY_PAGES) {
                Utils.log("findById: repeated timeouts, msgId=$msgId not found")
                result(null)
            } else {
                findByIdInternal(msgId, seqHint, onProgress, shouldContinue, repeatCount - 1, emptyPages + 1, result)
            }
        })
    }

    @Mixin
    class Hook : WatchAIOListVB() {
        @Suppress("UNCHECKED_CAST")
        override fun n(state: MsgListUiState, uiHelper: IListUIOperationApi) {
            vb = this
            uiOp = uiHelper
            val msg = msgList.value
            // MsgListState.updateType (public int field `c`): bit 0x4 marks an older-history
            // "load previous page" result; value 5 == LoadPrePageFail (top of history reached).
            // Captured here so [loadOlderPage] waiters can correlate to the actual page load.
            //
            // Read via the class HIERARCHY, not getDeclaredField on the runtime class: `c` is
            // declared on MsgListState, but `state` is typed as the MsgListUiState interface and the
            // concrete class is only guaranteed to be *some* implementation. A declared-only lookup
            // throws NoSuchFieldException for any subclass, which silently made updateType -1 →
            // [topPageResult] never fired → every page load fell through to its timeout, so a jump
            // over a few dozen pages could never finish. Also logs the raw value while logging is on,
            // because the bit meanings below are inferred from behavior, not documentation.
            val updateType = runCatching {
                var c: Class<*>? = state.javaClass
                while (c != null) {
                    val f = runCatching { c.getDeclaredField("c") }.getOrNull()
                    if (f != null && f.type == Integer.TYPE) {
                        f.isAccessible = true
                        return@runCatching f.getInt(state)
                    }
                    c = c.superclass
                }
                -1
            }.getOrElse { -1 }
            if (Utils.loggingEnabled) Utils.log("MsgList.n: updateType=$updateType (raw) stateCls=${state.javaClass.simpleName}")
            val list = state as LinkedList<WatchAIOMsgItem>
            // Diagnostic: capture what the kernel handed us vs our accumulated mirror, so an
            // empty RecyclerView (incoming list size 0 → blank chat) is distinguishable from a
            // render-side problem (non-zero list but cells invisible). peer ties it to the chat.
            if (Utils.loggingEnabled) Utils.log("MsgList.n: peer=${CurrentContact.peerUid} updateType=$updateType incomingSize=${list.size} mirrorSize=${msg.size} types=[${list.take(8).joinToString(",") { runCatching { "${it.d.msgType}/${it.javaClass.simpleName}" }.getOrElse { "?" } }}]")
            // Seq-positioned merge: reconcile the incoming state into the accumulated mirror and
            // render the merged result. Both lists are oldest-first.
            //
            // History of this merge:
            //  v1 (upstream/ours): break at the FIRST match from the newest end, render
            //      incoming-head + mirror-tail. BROKE on partial windows (status/read updates that
            //      carry only the newest few items): everything older than the window vanished from
            //      the render AND the truncation was written back into the mirror — the
            //      "messages missing until chat re-entry" bug.
            //  v2: reconcile every incoming item, unseen ones appended at the tail. BROKE older-page
            //      loads: a batch of history items the mirror had never seen got APPENDED AFTER the
            //      newest messages — completely scrambled order, broken jump targets.
            //
            //  v3 (this): walk incoming oldest→newest, keeping a cursor into the mirror:
            //    - a matched item emits the mirror items BEFORE its index that incoming didn't
            //      cover, then the updated item (order always preserved, nothing truncated);
            //    - an UNSEEN item is placed by msgSeq: mirror items older than it flush first, so
            //      older-page batches land BEFORE the known messages and live new messages land
            //      AFTER them — regardless of which side is newer;
            //    - duplicates in the incoming batch (already emitted) are skipped.
            //    The remaining mirror tail flushes last. The merged result becomes both the render
            //    list and the new mirror.
            // ── Fast path: the incoming window is just the newest slice of the mirror ──────────
            // (same msgIds, same order, nothing unseen, nothing older). This is the overwhelmingly
            // common push: a new incoming message, a read-receipt / status update, a sticker
            // refresh — the kernel re-hands the newest few items. The full merge below is O(loaded
            // history) per push (an ArrayList sized to the whole list + an msgId→index HashMap +
            // a branch per item), so on a chat that has been open for a while with history paged
            // in, EVERY such update walks thousands of items on the main thread. That is what makes
            // the list feel progressively heavier the longer a chat has been used — and it is
            // entirely avoidable when the window lines up with the tail we already hold.
            //
            // Older-history results (updateType bit 0x4) are excluded: those genuinely insert
            // items before the known ones and must go through the merge.
            val tailStart = msg.size - list.size
            if (list.isNotEmpty() && tailStart >= 0 && (updateType and 4) == 0) {
                var aligned = true
                run {
                    val inc = list.iterator()
                    var gi = tailStart
                    while (inc.hasNext()) {
                        if (gi >= msg.size || inc.next().d.msgId != msg[gi].d.msgId) {
                            aligned = false
                            break
                        }
                        gi++
                    }
                }
                if (aligned) {
                    var gi = tailStart
                    val inc = list.listIterator()
                    while (inc.hasNext()) {
                        val item = inc.next()
                        AntiRecallVault.put(item)
                        // Same anti-recall rule as the merge: a peer recall must not replace the
                        // original, so the mirror's kept item is written back into the render list.
                        val peerRecall = momoi.mod.qqpro.Settings.antiRecall.value &&
                            item.d.recallTime > 0L && item.d.senderUid != SelfContact.peerUid
                        if (peerRecall) {
                            inc.set(msg[gi])
                        } else {
                            msg[gi] = item
                        }
                        gi++
                    }
                    // Always notify: loadOlderPage / loadMoreMsg use the observer as a "state was
                    // pushed" wake-up, so skipping the call on an unchanged window would leave a
                    // waiter hanging. update() with the same instance does no copying.
                    msgList.update(msg)
                    // Hand the FULL mirror to the native render — same contract as the merge path,
                    // but as one arraycopy instead of a per-item rebuild.
                    list.clear()
                    list.addAll(msg)
                    forceBottomAlign()
                    super.n(list as MsgListUiState, uiHelper)
                    return
                }
            }

            val merged = ArrayList<WatchAIOMsgItem>(msg.size + list.size)
            val indexById = HashMap<Long, Int>(msg.size * 2)
            msg.forEachIndexed { i, it -> indexById.putIfAbsent(it.d.msgId, i) }
            var mi = 0
            fun flushMirrorOlderThan(seq: Long) {
                while (mi < msg.size && msg[mi].d.msgSeq < seq) { merged.add(msg[mi]); mi++ }
            }
            for (item in list) {
                // Cache the PRE-recall ITEM before the recall branches handle it (per peer, per
                // msgId). The vault stores the item itself — see [AntiRecallVault] for why a
                // rebuilt MsgRecord would crash the cell factory.
                AntiRecallVault.put(item)
                val j = indexById[item.d.msgId]
                if (j != null && j >= mi) {
                    // Anti-recall, peer recalls only: a recall arrives as an update of the ORIGINAL
                    // record with recallTime > 0. Skip EVERY such peer update — the kernel ALSO
                    // embeds a GrayTipElement into the recalled original record itself, so testing
                    // "is it a gray tip?" here would let the recall through (the message vanished).
                    // The separate "对方撤回了一条消息" gray-tip RECORD arrives as an unseen item
                    // and inserts via the branch below — that is the notice under the preserved
                    // message. My OWN recall (senderUid == self) passes and disappears normally.
                    val peerRecall = momoi.mod.qqpro.Settings.antiRecall.value &&
                        item.d.recallTime > 0L && item.d.senderUid != SelfContact.peerUid
                    if (peerRecall) {
                        Utils.log("MsgList.n: antiRecall kept original of peer-recalled msgId=${item.d.msgId}")
                        continue
                    }
                    while (mi < j) { merged.add(msg[mi]); mi++ }   // mirror-only items before it
                    merged.add(item)                               // updated in place
                    mi = j + 1
                } else if (j == null) {
                    // Anti-recall on reload: the kernel DB keeps the peer-recalled record (with
                    // recallTime > 0) — and its original content may already be replaced server-
                    // side. If we still hold the pre-recall snapshot, substitute the ORIGINAL ITEM
                    // for the loaded one so the original renders again (the item keeps its concrete
                    // subclass, so the cell factory stays type-correct). Without a snapshot (e.g.
                    // after an app restart), leave the loaded item untouched — rebuilding one would
                    // crash in WatchUnSupportItemCell. The recall gray tip still loads below it as
                    // the notice.
                    if (momoi.mod.qqpro.Settings.antiRecall.value &&
                        item.d.recallTime > 0L && item.d.senderUid != SelfContact.peerUid
                    ) {
                        val snap = AntiRecallVault.restoreItem(item.d.peerUid, item.d.msgId)
                        if (snap != null) {
                            Utils.log("MsgList.n: antiRecall restored msgId=${item.d.msgId} from vault on load")
                            flushMirrorOlderThan(snap.d.msgSeq)
                            merged.add(snap)
                            continue
                        }
                        item.d.recallTime = 0L
                        Utils.log("MsgList.n: antiRecall cleared recall flag of msgId=${item.d.msgId} on load")
                    }
                    flushMirrorOlderThan(item.d.msgSeq)            // unseen: place by seq
                    merged.add(item)
                } // else: duplicate msgId already emitted from the mirror — skip
            }
            while (mi < msg.size) { merged.add(msg[mi]); mi++ }    // flush the remaining mirror tail
            list.clear()
            list.addAll(merged)
            msgList.update(merged)
            Utils.log("MsgList.n: after merge finalSize=${list.size} (handing to native render)")
            // Notify pre-page waiters only for older-history results (bit 0x4), after msgList is
            // updated so they observe the new size.
            if (updateType and 4 != 0) topPageResult.update(updateType)
            // Tall-screen: pin the newest message to the bottom (see forceBottomAlign). Set before the
            // native render so the layout pass it triggers already sees needTopToBottom=false.
            forceBottomAlign()
            super.n(list as MsgListUiState, uiHelper)
        }
    }

    /**
     * Disable the native 120-message sliding-window eviction.
     *
     * [WatchMsgListRepo.o] (repo init) sets the base [MsgListRepo] elimination cap to 120 — runtime
     * field `MsgListRepo.d` (`msgLimitCnt`). Once the loaded list grows past it, the base repo trims on
     * EVERY page load:
     *   - loading OLDER (LoadPrePage): `subList(0, 120)` — keeps the OLDEST 120, DROPS the newest tail
     *     ("msgElimination: delete N at foot").
     *   - loading NEWER (LoadNextPage): keeps the newest 120, drops the head.
     *
     * So jumping more than ~120 messages up (reply-source jump, 跳转第一条未读, chat search) evicts the
     * NEWEST messages from the repo's own list. [Hook.n] re-heals the RENDERED list from our accumulated
     * [msgList] mirror, but the repo's [displayList] stays truncated — its lastOrNull() is now a mid-list
     * message, so the next LoadNextPage (fired when you scroll back down) anchors on the wrong message and
     * the newest messages stay missing until you re-enter the chat (which reloads a fresh first page).
     * That is the "messages near the end disappear after a far jump" bug.
     *
     * Fix: after the original init, set the cap to DISABLE_ELIMINATION (-1). The base guards trimming with
     * `size > i && i != DISABLE_ELIMINATION`, so -1 turns elimination off entirely. We already accumulate
     * the whole list in [msgList] and hand it to the renderer, so the repo retaining it too costs nothing
     * extra and just stops it from fighting the heal. The property has no surviving setter at runtime (R8
     * inlined `o()`'s assignment to a direct iput), so we write the field reflectively to avoid a
     * NoSuchMethod crash from a `setMsgLimitCnt` call. Re-applies on every chat (re)open since o() runs per
     * repo init.
     */
    @Mixin
    class NoEviction(context: AIOContext, scope: CoroutineScope) : WatchMsgListRepo(context, scope) {
        override fun o() {
            super.o() // original init (sets msgLimitCnt = 120, processors, name ability, …)
            runCatching {
                // Field `d` is declared on MsgListRepo (not the Watch/Compat subclasses, which may reuse
                // the short name `d` for their own fields), so target that exact declaring class.
                val f = MsgListRepo::class.java.getDeclaredField("d")
                f.isAccessible = true
                f.setInt(this, -1) // DISABLE_ELIMINATION
                Utils.log("MsgList.NoEviction: msgLimitCnt -> -1 (sliding-window eviction disabled)")
            }.onFailure { Utils.log("MsgList.NoEviction: failed to disable eviction: $it") }
        }

        override fun d(ids: MutableList<Long>) {
            if (momoi.mod.qqpro.Settings.antiRecall.value) {
                // Anti-recall blocks repo-level deletions so peer-recalled messages stay in the
                // live list — but the USER's own deletes must pass through, or the row survives in
                // the repo, the next push re-adds it, and it "revives" until chat re-entry.
                val localOnly = ids.isNotEmpty() && ids.all { LocalDeleteMarker.isLocal(it) }
                if (localOnly) {
                    Utils.log("MsgList.NoEviction: local delete d(ids=${ids.size}) allowed")
                    super.d(ids)
                } else {
                    Utils.log("MsgList.NoEviction: antiRecall blocked d(ids=$ids), message preserved")
                }
                return
            }
            super.d(ids)
        }
    }

    @Mixin
    class Clear(p0: IAIOFactory) : ChatPie(p0) {
        override fun a(
            fragment: ChatFragment,
            inflater: LayoutInflater,
            container: ViewGroup,
            isPreload: Boolean
        ): View {
            Utils.log("MsgList.Clear: resetting msgList mirror (isPreload=$isPreload)")
            msgList = Observable(ArrayList())
            scrollSettledSinceOpen = false
            return super.a(fragment, inflater, container, isPreload)
        }
    }

}