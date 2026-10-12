package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.api.impl.MsgService
import com.tencent.qqnt.kernel.api.impl.`MsgService$addDefaultListener$1`
import com.tencent.qqnt.kernel.nativeinterface.Contact
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import java.util.ArrayList

/**
 * Intercepts kernel-level message recall/delete notifications and — when [Settings.antiRecall] is
 * on — swallows them, so the kernel never processes the recall (DB delete / live-list eviction)
 * and the message stays. A toast marks the blocked recall.
 *
 * HOST CLASS MATTERS: the real app-wide listener is `MsgService.addDefaultListener$1` — an
 * anonymous `IKernelMsgListener` registered once at kernel init (MsgService.addDefaultListener).
 * An earlier attempt mixed into `com.tencent.qqnt.msg.MsgListenerAdapter` (the Kotlin no-op
 * adapter base) and NEVER RAN: concrete listeners implement IKernelMsgListener directly, so the
 * adapter's default bodies are never dispatched. Method bodies are injected into the host class
 * by ApkMixin; `super.x(...)` inside the injected body calls the host's ORIGINAL implementation
 * (same pattern as CallConnHook). Compiles thanks to the EnclosingMethod-stripped stub (see
 * build.gradle.kts).
 */
@Mixin
class AntiRecallHook(p0: MsgService) : `MsgService$addDefaultListener$1`(p0) {

    override fun onMsgRecall(chatType: Int, peerUid: String, msgId: Long) {
        if (Settings.antiRecall.value) {
            Utils.log("AntiRecall: blocked onMsgRecall msgId=$msgId chat=$peerUid")
            Utils.toast(Utils.application, "对方尝试撤回一条消息（已拦截）")
            return // swallow: the kernel never processes the recall
        }
        super.onMsgRecall(chatType, peerUid, msgId)
    }

    override fun onMsgDelete(contact: Contact, msgIds: ArrayList<Long>) {
        if (Settings.antiRecall.value) {
            // Remote-driven deletes ride the same broadcast; keep them from evicting the live list.
            // The user's OWN delete path (menu / multi-select) does not go through this listener —
            // it calls msgService.deleteMsg directly and refreshes via CurrentMsgList.removeLive.
            Utils.log("AntiRecall: blocked onMsgDelete ${msgIds.size} id(s)")
            return
        }
        super.onMsgDelete(contact, msgIds)
    }
}
