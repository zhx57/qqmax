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
import com.tencent.qqnt.kernel.nativeinterface.GrayTipElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
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
     * True when [r] is the gray-tip record announcing a recall ("xx 撤回了一条消息"): a gray tip
     * whose [GrayTipElement] carries a non-null revoke payload. Used by the anti-recall merge
     * filter so the notice never renders next to the preserved original message.
     */
    fun isRecallGrayTip(r: MsgRecord): Boolean =
        r.elements?.any { it.grayTipElement?.revokeElement != null } == true

    fun getMsgIndex(msg: WatchAIOMsgItem): Int {
        return msgList.value.indexOf(msg)
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
        runCatching {
            val live = uiOp?.m()
            if (live != null) {
                val i = live.indexOf(msg)
                if (i >= 0) return if (i > 0) live[i - 1] as? WatchAIOMsgItem else null
            }
        }.onFailure { Utils.log("prevMsg live lookup failed: $it") }
        val mi = msgList.value.indexOf(msg)
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
    private fun loadMoreMsg() {
        if (!isLoadingMsg) {
            msgList.observeOnce {
                isLoadingMsg = false
            }
            isLoadingMsg = true
            Utils.log("Load more msg. currentSize: ${msgList.value.size}")
            vb.L(MsgListDataIntent.LoadTopPage("WatchAIOListVB"))
        }
    }

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
        var settled = false
        topPageResult.observeOnce { updateType ->
            if (settled) return@observeOnce
            settled = true
            ThreadManager.runOnUiThread({ onResult(updateType == 5) })
        }
        ThreadManager.runOnUiThread({
            if (settled) return@runOnUiThread
            settled = true
            Utils.log("loadOlderPage: timed out waiting for pre-page result, size=${msgList.value.size}")
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
     * Scroll target is [count] messages above [current]. Pages in older messages until enough
     * history is loaded, then invokes [callback] with the resulting list position.
     *
     * [onProgress] is called with a 0..100 percentage after each page so the caller can show a
     * loading indicator. [onFail] fires (on the UI thread) if a page load times out or the top of
     * history is reached before the target — so the UI can show a toast and reset instead of
     * hanging silently.
     */
    fun upwardMsg(
        current: Int,
        count: Int,
        onProgress: (Int) -> Unit = {},
        onFail: () -> Unit = {},
        callback: (Int) -> Unit
    ) {
        val target = msgList.value.size - 1 - current + count
        upwardMsgInternal(target, msgList.value.size, onProgress, onFail, callback)
    }

    private fun upwardMsgInternal(
        target: Int,
        startSize: Int,
        onProgress: (Int) -> Unit,
        onFail: () -> Unit,
        callback: (Int) -> Unit
    ) {
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
        loadOlderPage(5000L, onResult = { reachedTop ->
            when {
                msgList.value.size >= target -> callback(msgList.value.size - target - 1)
                // LoadPrePageFail, or a pre-page that added nothing new -> top reached before target.
                reachedTop || msgList.value.size <= before -> {
                    Utils.log("upwardMsg: reached top of history before target=$target, size=${msgList.value.size}")
                    onFail()
                }
                else -> upwardMsgInternal(target, startSize, onProgress, onFail, callback)
            }
        }, onTimeout = onFail)
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
        val msg = msgList.value.find { it.d.msgSeq == seq }
        if (msg != null) {
            result(msg)
            return
        }
        if (repeatCount <= 0) {
            Utils.log("findMsg: give up (repeat exhausted) seq=$seq")
            result(null)
            return
        }
        // Load older messages and retry. Stop instead of hanging forever when the kernel reports
        // the top of history (LoadPrePageFail), a pre-page added nothing new, or no pre-page result
        // arrives within the timeout.
        val sizeBefore = msgList.value.size
        onProgress(sizeBefore) // how many messages are loaded so far while we keep paging up
        loadOlderPage(3000L, onResult = { reachedTop ->
            if (reachedTop || msgList.value.size <= sizeBefore) {
                Utils.log("findMsg: reached top of history, seq=$seq not found")
                result(null)
            } else {
                findMsg(seq, onProgress, result, repeatCount - 1)
            }
        }, onTimeout = { result(null) })
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
            val updateType = runCatching {
                state.javaClass.getDeclaredField("c").apply { isAccessible = true }.getInt(state)
            }.getOrElse { -1 }
            val list = state as LinkedList<WatchAIOMsgItem>
            // Diagnostic: capture what the kernel handed us vs our accumulated mirror, so an
            // empty RecyclerView (incoming list size 0 → blank chat) is distinguishable from a
            // render-side problem (non-zero list but cells invisible). peer ties it to the chat.
            Utils.log("MsgList.n: peer=${CurrentContact.peerUid} updateType=$updateType incomingSize=${list.size} mirrorSize=${msg.size} types=[${list.take(8).joinToString(",") { runCatching { "${it.d.msgType}/${it.javaClass.simpleName}" }.getOrElse { "?" } }}]")
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
            val merged = ArrayList<WatchAIOMsgItem>(msg.size + list.size)
            val indexById = HashMap<Long, Int>(msg.size * 2)
            msg.forEachIndexed { i, it -> indexById.putIfAbsent(it.d.msgId, i) }
            var mi = 0
            fun flushMirrorOlderThan(seq: Long) {
                while (mi < msg.size && msg[mi].d.msgSeq < seq) { merged.add(msg[mi]); mi++ }
            }
            for (item in list) {
                val j = indexById[item.d.msgId]
                if (j != null && j >= mi) {
                    // Anti-recall: a recall arrives as an update of the ORIGINAL record with
                    // recallTime > 0 (the kernel marks it recalled in place). Skip that update so
                    // the mirror keeps the pre-recall message content — the row never turns into a
                    // gray tip and never vanishes. First-seen records with recallTime > 0 (e.g.
                    // opening a chat whose history already contains an old recall) still load.
                    if (momoi.mod.qqpro.Settings.antiRecall.value && item.d.recallTime > 0L) {
                        Utils.log("MsgList.n: antiRecall kept original of recalled msgId=${item.d.msgId}")
                        continue
                    }
                    while (mi < j) { merged.add(msg[mi]); mi++ }   // mirror-only items before it
                    merged.add(item)                               // updated in place
                    mi = j + 1
                } else if (j == null) {
                    // Anti-recall: also drop the "xx 撤回了一条消息" gray-tip record itself —
                    // otherwise it lingers under the preserved original (visible after a local
                    // delete or a history reload pulls it in).
                    if (momoi.mod.qqpro.Settings.antiRecall.value && isRecallGrayTip(item.d)) {
                        Utils.log("MsgList.n: antiRecall dropped recall gray tip msgId=${item.d.msgId}")
                        continue
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
                Utils.log("MsgList.NoEviction: antiRecall blocked d(ids=$ids), message preserved")
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