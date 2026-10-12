package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.api.impl.MsgService
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/**
 * Intercepts kernel-level message deletion when a message is recalled by the other party.
 * When [Settings.antiRecall] is enabled, this prevents the kernel from deleting the message
 * from the local database, leaving the message intact in the chat history.
 */
@Mixin
class AntiRecallHook : MsgService() {

    override fun deleteRecallMsgForLocal(contact: Contact?, msgId: Long, callback: IOperateCallback?) {
        if (Settings.antiRecall.value) {
            Utils.log("AntiRecall: blocked deleteRecallMsgForLocal for msgId=$msgId")
            callback?.onResult(0, "")
            return
        }
        super.deleteRecallMsgForLocal(contact, msgId, callback)
    }

    override fun deleteRecallMsg(contact: Contact?, msgId: Long, callback: IOperateCallback?) {
        if (Settings.antiRecall.value) {
            Utils.log("AntiRecall: blocked deleteRecallMsg for msgId=$msgId")
            callback?.onResult(0, "")
            return
        }
        super.deleteRecallMsg(contact, msgId, callback)
    }
}
