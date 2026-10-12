package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.kernel.adapter.WatchAIOFetchService
import com.tencent.watch.aio_impl.kernel.adapter.`WatchAIOFetchService$registerMsgListener$2`
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import java.util.ArrayList

/**
 * Anti-recall: intercepts the AIO chat's own kernel listener — the ONLY listener in the watch app
 * with concrete (non-synthetic) overrides — so a recall never reaches the chat list.
 *
 * HOST CLASS MATTERS (two failed attempts documented here):
 *  1. `com.tencent.qqnt.msg.MsgListenerAdapter` — nothing in the app extends it; injecting the base
 *     was dead code.
 *  2. `MsgService$addDefaultListener$1` — implements IKernelMsgListener entirely via Kotlin `by`
 *     delegation: every interface method is ACC_SYNTHETIC (invisible to Kotlin, not overridable,
 *     no callable super) and its DefaultImpls `d.c.k.o.b.h.L` is a no-op. Not mixin-able.
 *
 * `WatchAIOFetchService.registerMsgListener` registers `…$registerMsgListener$2`, an anonymous
 * `IKernelMsgListener` with REAL overrides — onMsgDelete / onMsgInfoListUpdate / onRecvMsg are
 * concrete (ACC_PUBLIC, bodies present) and drive the open chat's repo. The native kernel still
 * updates its DB, but the visible list is fed by THIS listener, so:
 *  - [onMsgDelete]: swallowed while antiRecall is on — the live list keeps the messages.
 *  - [onMsgInfoListUpdate]: records carrying `recallTime > 0` (the recalled-update push) are
 *    filtered out, so the original message record survives in the list.
 * The user's own delete path (long-press menu / multi-select) calls msgService.deleteMsg directly
 * and refreshes via CurrentMsgList.removeLive — it does NOT depend on these callbacks.
 *
 * `super.x(...)` reaches the original implementation (same pattern as CallConnHook). Compiles
 * thanks to the EnclosingMethod-stripped stub (see build.gradle.kts).
 */
@Mixin
class AntiRecallHook(p0: WatchAIOFetchService) : `WatchAIOFetchService$registerMsgListener$2`(p0) {

    override fun onMsgDelete(contact: Contact, msgIds: ArrayList<Long>) {
        if (Settings.antiRecall.value) {
            Utils.log("AntiRecall: blocked onMsgDelete ${msgIds.size} id(s) chat=${contact.peerUid}")
            Utils.toast(Utils.application, "对方尝试撤回一条消息（已拦截）")
            return // swallow: the open chat list keeps the messages
        }
        super.onMsgDelete(contact, msgIds)
    }

    override fun onMsgInfoListUpdate(records: ArrayList<MsgRecord>) {
        if (Settings.antiRecall.value) {
            val kept = ArrayList<MsgRecord>(records.size)
            var blocked = 0
            for (r in records) {
                if (r.recallTime > 0L) blocked++ else kept.add(r)
            }
            if (blocked > 0) {
                Utils.log("AntiRecall: filtered $blocked recalled record(s) from onMsgInfoListUpdate")
                Utils.toast(Utils.application, "对方尝试撤回一条消息（已拦截）")
                super.onMsgInfoListUpdate(kept)
                return
            }
        }
        super.onMsgInfoListUpdate(records)
    }
}
