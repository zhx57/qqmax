package momoi.mod.qqpro.hook

import android.os.Bundle
import com.tencent.mobileqq.app.PrivacyPolicyHelper
import com.tencent.qqnt.watch.mainframe.MainActivity
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.ota.OTAManager2
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.watchdog.Watchdog

/**
 * Update check on launch. Delegates to [OTAManager2], which queries the GitLab Releases API of
 * https://gitlab.com/ailife8881/qqmax, compares the latest release tag against this app's own
 * versionName, and (if newer) prompts to download+install the release APK in-app. Respects the
 * user's "不再提醒" choice (stored by OTAManager2 itself).
 */
@Mixin
class 更新检查 : MainActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Remove the TOS/privacy-agreement interstitial: the splash (nav start destination, shown
        // during this activity's lifecycle, after onCreate) routes to PrivacyLicenseFragment unless
        // PrivacyPolicyHelper.b() == "1". Pre-mark it agreed here so that gate passes straight through
        // to login. Gated by materializeLogin; c() (the setter) isn't our hook, so no recursion.
        if (Settings.materializeLogin.value) {
            runCatching { PrivacyPolicyHelper.c("1") }
                .onFailure { Utils.log("LoginM3: pre-agree privacy failed: $it") }
        }
        super.onCreate(savedInstanceState)
        Watchdog.install(this)
        // Start online-presence polling once at launch if any status surface is enabled (the kernel
        // won't push presence otherwise). Cheap no-op when all status toggles are off. See OnlineStatus.
        if (Settings.anyOnlineStatus) momoi.mod.qqpro.hook.action.OnlineStatus.start()
        // The base QQ APK never requests Android 13+ POST_NOTIFICATIONS, so notifications are dropped
        // until granted. Ask on launch (no-op below API 33 / once already granted).
        NotificationPermission.ensure(this)
        OTAManager2(this).checkUpdate(false)
        // MIUIX is the only design system now (the dual-style chooser was removed). A stale
        // uiStyle==2 means an old build's 原始设计 pick one-shot disabled every MIUIX toggle and
        // persisted it — re-enable them once so the redesign actually applies after update.
        if (Settings.uiStyle.value == 2) {
            Settings.uiStyle.value = 1
            Settings.styleToggles.forEach { it.value = true }
            Utils.log("更新检查: legacy uiStyle==2 migration — all MIUIX toggles re-enabled")
        }
    }
}
