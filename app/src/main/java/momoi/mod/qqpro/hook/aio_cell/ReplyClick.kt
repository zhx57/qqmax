package momoi.mod.qqpro.hook.aio_cell

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.kernel.nativeinterface.ReplyElement
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.view.BubbleTextView
import momoi.mod.qqpro.hook.view.smoothScrollToSeqAndFlash
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3CircularProgress
import momoi.mod.qqpro.util.Utils

/**
 * "Tap a quoted message to jump to its source".
 *
 * Identifies the source by **msgId first, msgSeq second**. The old version searched by
 * `replayMsgSeq` alone, and that is why it "could not locate the message" after loading thousands
 * of them: a payload's msgSeq is not guaranteed to equal the seq of the locally synced record (a
 * server re-sync or a different delivery channel reassigns it), while msgId is assigned once and
 * never changes. See [CurrentMsgList.locateMessage] for the three-step lookup — the loaded list,
 * then one kernel DB call, then paging.
 *
 * Also honours [ReplyElement.sourceMsgExpired]: when the source is gone there is nothing to page
 * for, so the user gets an immediate, accurate message instead of a long search ending in failure.
 */
class ReplyClick(
    val widget: AIOCellGroupWidget,
    val reply: ReplyElement
) : View.OnClickListener {
    private var finding = false
    // Floating spinner + "加载中 …" pill shown while we page up looking for the source message, same
    // idea as the jump-to-first-unread progress. Removed once the search finishes.
    private var loading: TextView? = null
    private var loadingPill: View? = null

    override fun onClick(v: View?) {
        val rv = widget.parent as? RecyclerView ?: return
        if (finding) return
        finding = true
        // Remember where we are now and show the back-down button so the user can return here.
        BubbleTextView.beginJumpUp()
        showLoading(rv, "加载中…")

        // replayMsgId is a primitive long; replayMsgSeq is a BOXED Long and is legitimately null on
        // some payloads (e.g. sources resolved only by id), so it must never be unboxed blindly.
        val msgId = runCatching { reply.replayMsgId }.getOrDefault(0L)
        val seqHint = runCatching { reply.replayMsgSeq }.getOrNull()?.takeIf { it > 0L }
        val expired = runCatching { reply.sourceMsgExpired }.getOrDefault(false)

        fun finish(item: WatchAIOMsgItem?, reason: String?) {
            finding = false
            hideLoading()
            if (item != null) {
                // Jump by msgSeq and re-resolve the position at scroll time: fetching the source
                // page PREPENDS history, which shifts every adapter index, so a position taken
                // here would land on a neighbouring message.
                rv.smoothScrollToSeqAndFlash(item.d.msgSeq) { seq -> CurrentMsgList.indexOfSeqPublic(seq) }
            } else if (reason != null) {
                Utils.toast(rv.context, reason)
            }
        }

        if (expired) {
            finish(null, "原消息已过期，无法跳转")
            return
        }
        if (msgId <= 0L && seqHint == null) {
            finish(null, "这条引用缺少定位信息")
            return
        }

        // Cheap path first: if it is already loaded, the jump is instant and needs no paging at all.
        CurrentMsgList.findLoadedByIdOrSeq(msgId, seqHint)?.let {
            Utils.log("ReplyClick: source already loaded (msgId=$msgId seq=$seqHint) — no paging")
            finish(it, null)
            return
        }

        Utils.log("ReplyClick: locating msgId=$msgId seqHint=$seqHint fullSearch=${Settings.replyFullSearch.value}")
        CurrentMsgList.locateMessage(
            msgId = msgId,
            seqHint = seqHint,
            onProgress = { loaded -> loading?.text = "加载中 $loaded" },
            // Honour the optional "search the whole history" setting: OFF caps the search at ~1000
            // pages (≈10k messages) so a bogus reference can't churn through an enormous chat;
            // ON removes the cap and pages until the kernel says the top of history was reached.
            repeatLimit = if (Settings.replyFullSearch.value) 100_000 else 1_000,
            shouldContinue = { true },
        ) { item, reason ->
            if (item != null) {
                Utils.log("ReplyClick: located msgId=$msgId realSeq=${item.d.msgSeq}")
            } else {
                Utils.log("ReplyClick: NOT located msgId=$msgId seqHint=$seqHint reason=$reason")
            }
            finish(item, reason)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showLoading(rv: RecyclerView, text: String) {
        val decor = rv.rootView as? ViewGroup ?: return
        val spinner = M3CircularProgress(rv.context).apply {
            layoutParams = LinearLayout.LayoutParams(18.dp, 18.dp).apply { rightMargin = 8.dp }
        }
        val tv = TextView(rv.context).apply {
            this.text = text
            textSize = 13f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
        }
        val pill = LinearLayout(rv.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(18.dp, 12.dp, 20.dp, 12.dp)
            background = roundCornerDrawable(0xDD_303030.toInt(), 16.dp.toFloat())
            addView(spinner)
            addView(tv)
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        decor.addView(pill, lp)
        loading = tv
        loadingPill = pill
    }

    private fun hideLoading() {
        loadingPill?.let { (it.parent as? ViewGroup)?.removeView(it) }
        loading = null
        loadingPill = null
    }
}
