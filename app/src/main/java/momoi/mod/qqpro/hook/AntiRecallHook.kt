package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.msg.MsgListenerAdapter
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import java.util.ArrayList

/**
 * Intercepts kernel-level message deletion and recall broadcasts.
 * When [Settings.antiRecall] is enabled, this prevents the kernel from notifying
 * deletion/recall, leaving the message intact in the chat view and showing an intercepted notice.
 */
@Mixin
abstract class AntiRecallHook : MsgListenerAdapter() {

    override fun onMsgRecall(chatType: Int, peerUid: String, msgId: Long) {
        if (Settings.antiRecall.value) {
            Utils.log("AntiRecall: blocked onMsgRecall for msgId=$msgId in chat=$peerUid")
            Utils.toast(Utils.application, "对方尝试撤回一条消息（已拦截）")
        }
    }

    override fun onMsgDelete(contact: Contact, msgIds: ArrayList<Long>) {
        if (Settings.antiRecall.value) {
            Utils.log("AntiRecall: blocked onMsgDelete for msgIds=${msgIds.size}")
        }
    }
}
