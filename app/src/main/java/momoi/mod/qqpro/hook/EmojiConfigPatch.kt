package momoi.mod.qqpro.hook

import android.util.Log
import com.tencent.mobileqq.emoticon.QQSysFaceResImpl
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.BigfaceRes
import momoi.mod.qqpro.util.Utils
import org.json.JSONObject

/**
 * Makes newer emoji render in CHAT on the older watch QQ build:
 *  - classic small sysface (/足球 /礼物 …): config-only — their f_static_* drawables already ship.
 *  - 大表情 animated (大表情 lottie ids ~419..469): config + bundled lottie unzipped on first launch.
 *
 * Both are injected into the `sysface` array before the original [parseConfigData] builds its lookup
 * maps. The unzip+entry-building logic lives in [BigfaceRes] (a non-inline lib helper) per the
 * anon-class rule. See EMOJI_BIGFACE_PLAN.md.
 */
@Mixin
class EmojiConfigPatch : QQSysFaceResImpl() {

    override fun parseConfigData(faceConfig: JSONObject?, aniSticker: JSONObject?) {
        try {
            // Place the bundled lottie/static/apng files where the renderer expects them.
            //
            // ASYNC on purpose. This method is called SYNCHRONOUSLY by the kernel during startup,
            // and the extraction is an 8 MB archive of thousands of small files — doing it inline
            // blocked the main thread for long enough to be the "点击图标后卡在启动器一两秒"
            // delay on first launch (and after every update that bumps BigfaceRes.VERSION). The
            // worker thread does the unzip; this call only stats the marker file.
            BigfaceRes.ensureExtractedAsync(Utils.application)

            val sysface = faceConfig?.optJSONArray("sysface")
            if (sysface != null) {
                val added = BigfaceRes.appendMissingSysface(Utils.application, sysface)
                Utils.log("EmojiConfigPatch: injected $added sysface entries, total=${sysface.length()}")
            } else {
                Utils.log("EmojiConfigPatch: no sysface array to patch (faceConfig=$faceConfig)")
            }
        } catch (e: Throwable) {
            Utils.log("EmojiConfigPatch error: ${Log.getStackTraceString(e)}")
        }
        super.parseConfigData(faceConfig, aniSticker)
    }
}
