package momoi.mod.qqpro.hook

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.chats.core.adapter.itemdata.RecentContactChatItem
import com.tencent.qqnt.kernel.nativeinterface.DeleteRecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelRecentContactListener
import com.tencent.qqnt.kernel.nativeinterface.NotificationCommonInfo
import com.tencent.qqnt.kernel.nativeinterface.RecentContactExtra
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.RecentContactListChangedInfo
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.watch.ui.componet.tablayout.CircleIndicator
import com.tencent.watch.qzone_impl.alert.QZoneFeedAlertService
import java.lang.ref.WeakReference
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.hook.action.RecentContacts
import momoi.mod.qqpro.hook.view.smoothScrollToStart
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Home/main page (conversation list) bottom navigation. We HIDE the native [CircleIndicator] and
 * build our own fully independent nav bar so nothing fights us: the native indicator re-styles its
 * children on every scroll (causing distortion) and its wired ViewPager2 isn't the pager the watch
 * actually swipes. Our bar drives whatever content pager is live (v1 androidx ViewPager *or*
 * ViewPager2) via reflection, so taps switch pages and swipes update the highlight.
 *
 * Features (settings under 主页导航):
 *  - icon for EVERY page, selected tinted blue ([Settings.mainNavAllIcons]),
 *  - spread evenly across the width in square mode ([Settings.mainNavSquare]),
 *  - independent bar/icon height ([Settings.mainNavHeight]),
 *  - bottom placement ([Settings.bottomMainNav]),
 *  - per-page unread badges ([Settings.mainNavUnread]).
 */
object MainNav {
    private const val NAV_TAG = "qqpro_main_nav"
    private const val QZONE_POLL_MS = 8000L   // re-read the QZone undeal count this often
    // Material 3 (dark) palette. The selected item sits on a tonal "active indicator" pill.
    // WeChat-LiquidGlass palette: crisp white active icon, frosted inactive, luminous droplet pill
    private val ACCENT get() = 0xFFFFFFFF.toInt()
    private val PILL_COLOR get() = 0x28FFFFFF
    private val IDLE_ICON get() = 0x88FFFFFF.toInt()
    private val DOT_COLOR get() = 0x80FFFFFF.toInt()

    private var isNavHidden = false
    private var normalTranslationY = 0f

    fun hideNav(nav: View) {
        if (isNavHidden || !Settings.mainNavAutoHide.value) return
        val parent = nav.parent as? View ?: return
        isNavHidden = true
        val hiddenY = (parent.height - nav.top).toFloat() + 24.dp
        nav.animate()
            .translationY(hiddenY)
            .setDuration(220)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    fun showNav(nav: View) {
        if (!isNavHidden) return
        isNavHidden = false
        nav.animate()
            .translationY(normalTranslationY)
            .setDuration(220)
            .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
    }

    /**
     * Liquid-glass bar surface, ported from liuran001/WeChat-LiquidGlass (LiquidGlassPanel + LiquidGlassHostLayout).
     *
     * Features:
     *  - WeChat-LiquidGlass surfaceContainer wash: 0x662C2C2E in dark mode (40% #2C2C2E) / 0x66F2F2F7 in light mode.
     *  - Top curved specular gloss: linear gradient fading across top 45% of the capsule.
     *  - Soft elevation drop shadow: 8dp blur ambient shadow underneath.
     *  - Crisp highlight rim: 1dp stroke with 0x22FFFFFF.
     *  - Capsule geometry: corner radius = height × 50%.
     */
    private class LiquidGlassDrawable(val dark: Boolean) : android.graphics.drawable.Drawable() {
        private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.dp.toFloat()
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(8.dp.toFloat(), 0f, 2.dp.toFloat(), if (dark) 0x33000000 else 0x1A000000)
        }
        private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x28FFFFFF
        }
        private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.dp.toFloat()
            color = 0x4DFFFFFF
        }
        private val path = Path()
        private val rect = RectF()
        private val pillRect = RectF()

        /** Real-time dynamic blurred backdrop captured from the underlying content. */
        var backdropBitmap: Bitmap? = null

        /** Continuous floating crystal pill metrics */
        var pillCenterX: Float = -1f
        var pillWidth: Float = 0f
        var pillHeight: Float = 0f

        /** Center X coordinate of the active tab for rendering the interactive highlight bloom. */
        var activeTabCenterX: Float = -1f

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.isEmpty) return
            val h = bounds.height().toFloat()

            // 1. WeChat-LiquidGlass surface paint: 0x662C2C2E (40% #2C2C2E in dark mode)
            surfacePaint.color = if (dark) 0x662C2C2E else 0x66F2F2F7

            // 2. WeChat-LiquidGlass top gloss: specular white sheen across top 45% of the bar
            glossPaint.shader = android.graphics.LinearGradient(
                0f, bounds.top.toFloat(),
                0f, bounds.top + h * 0.45f,
                if (dark) 0x24FFFFFF else 0x40FFFFFF,
                0x00FFFFFF,
                Shader.TileMode.CLAMP
            )

            // 3. WeChat-LiquidGlass highlight rim: dual-source crisp light outline
            val steps = 90
            val strokeColors = IntArray(steps)
            val baseAlpha = if (dark) 0.45f else 0.65f
            for (i in 0 until steps) {
                val theta = Math.toRadians((i * 360.0 / steps))
                val l1 = Math.cos(theta + Math.toRadians(45.0)).toFloat()
                val l2 = Math.cos(theta - Math.toRadians(90.0)).toFloat()
                val brightness = (0.25f + 0.75f * (l1 * l1) + 0.35f * (l2 * l2)).coerceIn(0f, 1f)
                val alpha = (brightness * baseAlpha * 255).toInt().coerceIn(0, 255)
                strokeColors[i] = Color.argb(alpha, 255, 255, 255)
            }
            strokePaint.shader = SweepGradient(bounds.exactCenterX(), bounds.exactCenterY(), strokeColors, null)
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return
            rect.set(b)
            val r = rect.height().coerceAtMost(rect.width()) / 2f

            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)

            // 1. WeChat-LiquidGlass drop shadow: punch the pill out of the shadow
            // so the translucent glass interior stays crystal clear and never muddy.
            if (Build.VERSION.SDK_INT >= 26) {
                canvas.save()
                canvas.clipOutPath(path)
                canvas.drawPath(path, shadowPaint)
                canvas.restore()
            }

            canvas.save()
            canvas.clipPath(path)

            // 2. Real-time dynamic blurred backdrop
            backdropBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    canvas.drawBitmap(bmp, src, rect, backdropPaint)
                }
            }

            // 3. WeChat-LiquidGlass surface fill
            canvas.drawRect(rect, surfacePaint)

            // 4. WeChat-LiquidGlass specular gloss
            canvas.drawRect(rect, glossPaint)

            // 5. Continuous gliding crystal droplet pill
            if (pillCenterX >= 0f && pillWidth > 0f && pillHeight > 0f) {
                val pl = pillCenterX - pillWidth / 2f
                val pt = rect.centerY() - pillHeight / 2f
                val pr = pillCenterX + pillWidth / 2f
                val pb = rect.centerY() + pillHeight / 2f
                pillRect.set(pl, pt, pr, pb)
                val pillR = pillHeight / 2f
                canvas.drawRoundRect(pillRect, pillR, pillR, pillPaint)
                canvas.drawRoundRect(pillRect, pillR, pillR, pillStrokePaint)
            }

            // 6. Interactive highlight: soft radial bloom behind the selected tab
            if (activeTabCenterX >= 0f) {
                haloPaint.shader = RadialGradient(
                    activeTabCenterX,
                    rect.centerY(),
                    rect.height() * 1.35f,
                    Color.argb(55, 255, 255, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect, haloPaint)
            }
            canvas.restore()

            // 5. Dual-light crisp edge outline
            val strokeRect = RectF(rect)
            strokeRect.inset(strokePaint.strokeWidth * 0.5f, strokePaint.strokeWidth * 0.5f)
            val strokePath = Path()
            val strR = strokeRect.height().coerceAtMost(strokeRect.width()) / 2f
            strokePath.addRoundRect(strokeRect, strR, strR, Path.Direction.CW)
            canvas.drawPath(strokePath, strokePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** High-performance 3-pass BoxBlur approximating Gaussian blur on downscaled bitmap. */
    private object StackBlur {
        fun blur(bmp: Bitmap, radius: Int) {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0 || radius < 1) return
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            val temp = IntArray(w * h)
            val wm = w - 1
            val hm = h - 1
            val div = radius + radius + 1
            for (pass in 0 until 3) {
                boxBlurH(pixels, temp, w, h, radius, wm, div)
                boxBlurV(temp, pixels, w, h, radius, hm, div)
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, wm: Int, div: Int) {
            for (y in 0 until h) {
                val rowStart = y * w
                var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
                for (i in -radius..radius) {
                    val p = src[rowStart + Math.min(wm, Math.max(0, i))]
                    aSum += (p ushr 24) and 0xFF
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                }
                for (x in 0 until w) {
                    dst[rowStart + x] = ((aSum / div) shl 24) or
                            ((rSum / div) shl 16) or
                            ((gSum / div) shl 8) or
                            (bSum / div)
                    val outX = Math.max(x - radius, 0)
                    val inX = Math.min(x + radius + 1, wm)
                    val pOut = src[rowStart + outX]
                    val pIn = src[rowStart + inX]
                    aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                    rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                    gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                    bSum += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }
        }

        private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, hm: Int, div: Int) {
            for (x in 0 until w) {
                var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
                for (i in -radius..radius) {
                    val p = src[Math.min(hm, Math.max(0, i)) * w + x]
                    aSum += (p ushr 24) and 0xFF
                    rSum += (p ushr 16) and 0xFF
                    gSum += (p ushr 8) and 0xFF
                    bSum += p and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = ((aSum / div) shl 24) or
                            ((rSum / div) shl 16) or
                            ((gSum / div) shl 8) or
                            (bSum / div)
                    val outY = Math.max(y - radius, 0)
                    val inY = Math.min(y + radius + 1, hm)
                    val pOut = src[outY * w + x]
                    val pIn = src[inY * w + x]
                    aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                    rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                    gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                    bSum += (pIn and 0xFF) - (pOut and 0xFF)
                }
            }
        }
    }

    /** Real-time optical backdrop blur engine for Android 11 and all versions. */
    private object BlurEngine {
        private var blurBitmap: Bitmap? = null
        private var blurCanvas: Canvas? = null
        private var lastCaptureTime = 0L
        private val locNav = IntArray(2)
        private val locPager = IntArray(2)

        fun capture(nav: View, pagerView: View) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastCaptureTime < 28L) return // ~35 FPS throttle for smooth low-CPU execution
            val nw = nav.width
            val nh = nav.height
            val pw = pagerView.width
            val ph = pagerView.height
            if (nw <= 0 || nh <= 0 || pw <= 0 || ph <= 0) return
            if (!pagerView.isShown || !nav.isShown) return

            nav.getLocationOnScreen(locNav)
            pagerView.getLocationOnScreen(locPager)
            val dx = locNav[0] - locPager[0]
            val dy = locNav[1] - locPager[1]

            val scale = 0.35f
            val bw = Math.max((nw * scale).toInt(), 1)
            val bh = Math.max((nh * scale).toInt(), 1)

            if (blurBitmap == null || blurBitmap!!.isRecycled || blurBitmap!!.width != bw || blurBitmap!!.height != bh) {
                blurBitmap?.recycle()
                blurBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                blurCanvas = Canvas(blurBitmap!!)
            }

            val bmp = blurBitmap ?: return
            val c = blurCanvas ?: return

            lastCaptureTime = now
            runCatching {
                bmp.eraseColor(Color.TRANSPARENT)
                c.save()
                c.scale(scale, scale)
                c.translate(-dx.toFloat(), -dy.toFloat())
                pagerView.draw(c)
                c.restore()

                StackBlur.blur(bmp, 3)

                (nav.background as? LiquidGlassDrawable)?.let { bg ->
                    bg.backdropBitmap = bmp
                    bg.invalidateSelf()
                }
            }
        }
    }

    /** Custom LinearLayout to intercept and handle horizontal gestures for continuous page dragging. */
    class NavLinearLayout(ctx: android.content.Context) : LinearLayout(ctx) {
        var dragHandler: ((android.view.MotionEvent) -> Boolean)? = null
        var interceptHandler: ((android.view.MotionEvent) -> Boolean)? = null

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (interceptHandler?.invoke(ev) == true) return true
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (dragHandler?.invoke(ev) == true) return true
            return super.onTouchEvent(ev)
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != View.VISIBLE) {
                this.visibility = View.GONE
            }
        }
    }

    private val BADGE_COLOR get() = M3.badge

    // Live unread, kept in sync by the kernel listener — same source RichTitlebar uses.
    // ConcurrentHashMap: the kernel UnreadListener mutates this on a binder thread while the
    // high-frequency scroll listener iterates it on the UI thread (messagesUnread). A plain
    // HashMap there threw ConcurrentModificationException; the concurrent map's weakly-consistent
    // iterator never does.
    private val unread = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var listenerAdded = false

    // Published by ContactListFragmentHook (friend + group notification counts). Best-effort: only
    // fresh once the contacts page has been opened.
    @JvmField var contactUnread = 0
    @JvmField var contactFriendUnread = 0
    @JvmField var contactGroupUnread = 0

    // Live fragment refs published by the contacts / qzone page hooks so a repeat-tap on the nav can
    // reach into the page that's currently shown and open its notification screen. WeakReference so a
    // recreated (FragmentStateAdapter) fragment isn't leaked. See [openContactNotify]/[openQzoneNotify].
    @JvmField var contactFragment: WeakReference<Any>? = null
    @JvmField var qzoneFragment: WeakReference<Any>? = null

    // Round-robin cursor over the chat list's unread conversations (repeat-tap on the messages page).
    private var chatUnreadCursor = 0

    private var active: NavState? = null

    // M3 motion: emphasized-decelerate easing for the active-indicator reveal + icon transitions.
    private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
    private const val DUR_IN = 300L     // indicator/icon appearing (selected)
    private const val DUR_OUT = 200L    // indicator/icon disappearing (deselected)

    class NavState(
        val nav: LinearLayout,
        val pager: PagerCtl,
        val iconMap: Map<Int, String>,
        val labelMap: Map<Int, String>,
        val pageCount: Int,
    ) {
        val cells = ArrayList<Cell>()
        var current = 0
        // Signature of the layout-affecting settings (铺开/标签/图标数/高度) the cells were built
        // with; render() rebuilds the cells when it changes, so those switches apply instantly.
        var layoutKey = ""
        // Last fully-rendered (page, unread-signature) so the high-frequency scroll listener only
        // re-styles when something actually changed.
        var renderedKey = ""
        // Last selected page actually rendered — distinguishes a pure page switch (animate) from a
        // settings/badge change or first paint (instant).
        var lastCurrent = -1
    }

    class Cell(
        val frame: FrameLayout, val pill: View, val icon: ImageView, val dot: View,
        val badge: TextView, val label: TextView? = null,
    ) {
        // The icon's currently-displayed tint, so a selection change can crossfade from it.
        var iconColor = 0
        // Running icon color crossfade, cancelled before starting a new one.
        var colorAnim: ValueAnimator? = null
    }

    /** Live unread updates pushed by the kernel; recompute badges in place. */
    class UnreadListener : IKernelRecentContactListener {
        override fun onMsgUnreadCountUpdate(map: HashMap<String, Int>?) {
            if (map != null) { unread.putAll(map); refresh() }
        }
        override fun onRecentContactListChanged(
            sorted: ArrayList<Long>?, changed: ArrayList<RecentContactInfo>?, extra: RecentContactExtra?,
        ) { changed?.forEach { put(it) }; refresh() }
        override fun onRecentContactListChangedVer2(list: ArrayList<RecentContactListChangedInfo>?, seq: Int) {
            list?.forEach { it.changedList?.forEach { c -> put(c) } }; refresh()
        }
        override fun onRecentContactNotification(
            list: ArrayList<RecentContactInfo>?, common: NotificationCommonInfo?, seq: Int,
        ) { list?.forEach { put(it) }; refresh() }
        override fun onGuildDisplayRecentContactListChanged(list: ArrayList<RecentContactListChangedInfo>?) {}
        override fun onDeletedContactsNotify(list: ArrayList<DeleteRecentContactInfo>?) {}
    }

    private fun put(c: RecentContactInfo?) {
        val uid = c?.peerUid ?: return
        if (uid.isEmpty()) return
        unread[uid] = c.unreadCnt.toInt()
        // Capture the muted/DND state the kernel carries so DND chats are excluded from the badge
        // total even before their list row renders (see RecentContacts.mutedMap).
        RecentContacts.recordMuted(uid, RecentContacts.isMuted(c.isMsgDisturb, c.shieldFlag))
    }

    private fun messagesUnread(): Int {
        RecentContacts.map.forEach { (k, v) -> if (!unread.containsKey(k)) unread[k] = v.unreadCntCached }
        return unread.entries
            .filter { it.value > 0 && !RecentContacts.isDisturb(it.key) }
            .sumOf { it.value }
    }

    private fun unreadFor(state: NavState, page: Int): Int = when {
        page == state.pageCount - 1 -> 0   // last page = self/settings, never badged
        page == 0 -> messagesUnread()
        page == 1 -> contactUnread
        page == 2 -> qzoneUnread()         // QZone 互动通知 (likes/comments/@) undeal count
        else -> 0
    }

    /**
     * QZone interaction-notification undeal count (likes/comments/@mentions on your feeds) — the same
     * number QZoneMainFrame.f0() puts on its QUIBadge. Read live from the in-memory cache of
     * [QZoneFeedAlertService] (type 1 = interaction notifications; it self-loads on first call and is
     * kept fresh by the service's own DB observer). There is no separate "friend posted a new feed"
     * signal on this watch build, so only interaction notifications are badged. 0 when unavailable.
     */
    private fun qzoneUnread(): Int = runCatching {
        QZoneFeedAlertService.h()?.i(1)?.takeIf { it > 0 } ?: 0
    }.getOrDefault(0)

    private var settingsListenerAdded = false

    private fun ensureSettingsListener() {
        if (settingsListenerAdded) return
        settingsListenerAdded = true
        Settings.sp.registerOnSharedPreferenceChangeListener { _, key ->
            if (key != null && (key.startsWith("mainNav") || key == "bottomMainNav")) {
                momoi.mod.qqpro.util.ThreadManager.runOnUiThread({
                    active?.let { state ->
                        showNav(state.nav)
                        render(state, force = true)
                    }
                })
            }
        }
    }

    private fun ensureListener() {
        ensureSettingsListener()
        if (listenerAdded) return
        runCatching {
            KernelServiceUtil.g()?.recentContactService?.addKernelRecentContactListener(UnreadListener())
            listenerAdded = true
        }.onFailure { Utils.log("MainNav: listener register failed: $it") }
    }

    fun refresh() {
        val state = active ?: return
        state.nav.post { render(state, force = true) }
    }

    fun install(root: ViewGroup) {
        runCatching {
            val indicator = root.findAll { it is CircleIndicator } as? CircleIndicator
                ?: run { Utils.log("MainNav: indicator not found"); return }
            val parent = indicator.parent as? ViewGroup
                ?: run { Utils.log("MainNav: indicator has no parent"); return }
            // The real content pager is the ViewPager2 (MainFragment.vp); the tree may also contain
            // an unrelated v1 ViewPager, so match ViewPager2 first (same as the 屏蔽返回键 hook).
            val pagerView = root.findAll { it.javaClass.name.endsWith("ViewPager2") }
                ?: root.findAll { it !== indicator && it.javaClass.name.contains("ViewPager") }
                ?: run { Utils.log("MainNav: pager not found"); return }
            Utils.log("MainNav: pager=${pagerView.javaClass.name}")
            val pager = PagerCtl(pagerView)
            val pageCount = pager.count()
            if (pageCount <= 0) { Utils.log("MainNav: empty pager"); return }
            val iconMap = materialIconMapOf(pageCount)
            val labelMap = materialLabelMapOf(pageCount)

            // Remove a nav we built on a previous onViewCreated (returning to the home page).
            (parent.findAll { it.tag == NAV_TAG } as? ViewGroup)?.let { parent.removeView(it) }
            active?.pager?.removeListener()

            // Hide the native indicator entirely; we render our own.
            indicator.visibility = View.GONE

            val nav = NavLinearLayout(parent.context).apply {
                tag = NAV_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                background = LiquidGlassDrawable(!Settings.lightMode.value)
                val padH = if (Settings.mainNavSquare.value) 12.dp else 10.dp
                setPadding(padH, 2.dp, padH, 2.dp)
                // Stay hidden until positionBar has placed us (bottom/top); otherwise the bar paints
                // one frame at its default y=0 (top) before the deferred post moves it down — a visible
                // flash at the top on every return to the home page in bottom mode.
                visibility = View.INVISIBLE

                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (left != oldLeft || right != oldRight || top != oldTop || bottom != oldBottom) {
                        val curW = right - left
                        val p = parent as? View ?: return@addOnLayoutChangeListener
                        val pW = p.width
                        val pH = p.height
                        if (pW <= 0 || pH <= 0) return@addOnLayoutChangeListener
                        val isSpread = Settings.mainNavSquare.value || Settings.mainNavLabels.value
                        val gap = if (isSpread) 4.dp else 12.dp
                        val isBottom = Settings.bottomMainNav.value
                        if (isBottom) {
                            translationY = (pH - p.paddingBottom - (bottom - top) - gap).toFloat() - top.toFloat()
                        } else {
                            translationY = (p.paddingTop + gap).toFloat() - top.toFloat()
                        }
                        if (isSpread) {
                            translationX = 12.dp.toFloat() - left.toFloat()
                        } else if (curW > 0 && pW > curW) {
                            translationX = ((pW - curW) / 2f) - left.toFloat()
                        }
                    }
                }
            }
            val state = NavState(nav, pager, iconMap, labelMap, pageCount)
            state.current = pager.current()
            active = state

            val internalPagerRv = (pagerView as? ViewGroup)?.let { vg ->
                (0 until vg.childCount).map { vg.getChildAt(it) }.firstOrNull { it is RecyclerView } as? RecyclerView
            }

            val touchSlop = android.view.ViewConfiguration.get(parent.context).scaledTouchSlop
            var downRawX = 0f
            var downRawY = 0f
            var lastRawX = 0f
            var isDragging = false

            nav.interceptHandler = { ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        lastRawX = ev.rawX
                        isDragging = false
                        showNav(nav)
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(ev.rawX - downRawX)
                        val dy = Math.abs(ev.rawY - downRawY)
                        if (dx > touchSlop && dx > dy) {
                            isDragging = true
                            nav.parent?.requestDisallowInterceptTouchEvent(true)
                            showNav(nav)
                            true
                        } else {
                            false
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        false
                    }
                    else -> false
                }
            }

            nav.dragHandler = { ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        lastRawX = ev.rawX
                        showNav(nav)
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - lastRawX
                        lastRawX = ev.rawX
                        val totalDx = Math.abs(ev.rawX - downRawX)
                        val totalDy = Math.abs(ev.rawY - downRawY)
                        if (!isDragging && totalDx > touchSlop && totalDx > totalDy) {
                            isDragging = true
                            nav.parent?.requestDisallowInterceptTouchEvent(true)
                            showNav(nav)
                        }
                        if (isDragging) {
                            val nw = nav.width.toFloat().coerceAtLeast(1f)
                            val pw = pagerView.width.toFloat().takeIf { it > 0f } ?: nav.resources.displayMetrics.widthPixels.toFloat()
                            val tabW = nw / pageCount.toFloat().coerceAtLeast(1f)
                            val activeSpan = (nw - tabW).coerceAtLeast(1f)
                            val localX = ev.x.coerceIn(0f, nw)
                            val progress = ((localX - tabW * 0.5f) / activeSpan).coerceIn(0f, 1f) * (pageCount - 1)

                            if (internalPagerRv != null) {
                                val maxScroll = ((pageCount - 1) * pw).toInt().coerceAtLeast(0)
                                val targetScrollX = Math.round(progress * pw).toInt().coerceIn(0, maxScroll)
                                val currentScrollX = internalPagerRv.computeHorizontalScrollOffset()
                                val delta = targetScrollX - currentScrollX
                                if (delta != 0) {
                                    internalPagerRv.scrollBy(delta, 0)
                                }
                            }

                            (nav.background as? LiquidGlassDrawable)?.let { bg ->
                                pillAnimator?.cancel()
                                val iconSize = Settings.mainNavHeight.value.toInt().coerceIn(8, 64).dp
                                bg.pillWidth = (iconSize * 1.6f)
                                bg.pillHeight = (iconSize + 6.dp).toFloat()
                                bg.pillCenterX = localX
                                bg.activeTabCenterX = localX
                                bg.invalidateSelf()
                            }

                            val targetIndex = Math.round(progress).toInt().coerceIn(0, pageCount - 1)
                            if (targetIndex != state.current) {
                                state.current = targetIndex
                                state.cells.forEachIndexed { i, cell ->
                                    val sel = i == targetIndex
                                    cell.label?.setTextColor(if (sel) ACCENT else IDLE_ICON)
                                    cell.icon.setColorFilter(if (sel) ACCENT else IDLE_ICON)
                                    cell.icon.scaleX = if (sel) 1.1f else 1.0f
                                    cell.icon.scaleY = if (sel) 1.1f else 1.0f
                                }
                            }
                            BlurEngine.capture(nav, pagerView)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            isDragging = false
                            val nw = nav.width.toFloat().coerceAtLeast(1f)
                            val tabW = nw / pageCount.toFloat().coerceAtLeast(1f)
                            val activeSpan = (nw - tabW).coerceAtLeast(1f)
                            val localX = ev.x.coerceIn(0f, nw)
                            val progress = ((localX - tabW * 0.5f) / activeSpan).coerceIn(0f, 1f) * (pageCount - 1)
                            val targetIndex = Math.round(progress).toInt().coerceIn(0, pageCount - 1)
                            pager.setCurrent(targetIndex)
                            (nav.background as? LiquidGlassDrawable)?.let { bg ->
                                val targetCenterX = state.cells.getOrNull(targetIndex)?.frame?.let { it.left + it.width / 2f }
                                    ?: (tabW * (targetIndex + 0.5f))
                                animatePillTo(bg, targetCenterX, animate = true)
                            }
                            render(state, force = true)
                            BlurEngine.capture(nav, pagerView)
                        }
                        true
                    }
                    else -> false
                }
            }

            buildCells(state)
            parent.clipChildren = false
            parent.clipToPadding = false
            val barHeight = barHeightPx()

            val square = Settings.mainNavSquare.value
            val labels = Settings.mainNavLabels.value
            val isSpread = square || labels
            val targetWidth = if (isSpread) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            parent.addView(nav, ViewGroup.LayoutParams(targetWidth, barHeight))
            nav.bringToFront()
            if (Build.VERSION.SDK_INT >= 21) {
                nav.elevation = 0f
                nav.translationZ = 0f
            }
            updateNavLayoutParams(state)

            pager.observe {
                render(state)
                ensurePagePadding(pagerView, barHeightPx() + 14.dp)
                BlurEngine.capture(nav, pagerView)
            }
            positionBar(parent, pagerView, indicator, nav, barHeight)
            nav.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                if (hasFocus && active === state) {
                    render(state, force = true)
                }
            }

            ensureListener()
            render(state)
            scheduleQzonePoll(state)
        }.onFailure { Utils.log("MainNav install failed: $it") }
    }

    /**
     * The QZone undeal count has no kernel listener we hook (it lives behind an annotation-based
     * EventCenter), so re-render periodically to pick up new 互动通知 while the home page is visible.
     * Posting on the nav view means the loop naturally pauses while the page is detached (View
     * run-queue) and stops for good once a newer nav replaces this one (active !== state). render()
     * is gated by renderedKey, so an unchanged tick is a cheap no-op.
     */
    private fun scheduleQzonePoll(state: NavState) {
        val nav = state.nav
        nav.postDelayed(object : Runnable {
            override fun run() {
                if (active !== state) return
                render(state)
                nav.postDelayed(this, QZONE_POLL_MS)
            }
        }, QZONE_POLL_MS)
    }

    /**
     * Custom nav disabled: leave the native page-indicator as-is but still move it to the bottom
     * when [Settings.bottomMainNav] is on (the pre-session behavior). Top mode = native default.
     */
    fun installNative(root: ViewGroup) {
        if (!Settings.bottomMainNav.value) return
        runCatching {
            val indicator = root.findAll { it is CircleIndicator } as? CircleIndicator
                ?: run { Utils.log("MainNav(native): indicator not found"); return }
            val scale = (Settings.mainNavHeight.value / 16f).coerceIn(1f, 3f)
            indicator.scaleX = scale
            indicator.scaleY = scale
            (indicator.parent as? ViewGroup)?.apply { clipChildren = false; clipToPadding = false }
            indicator.post {
                runCatching {
                    val parent = indicator.parent as? View ?: return@post
                    val vp = root.findAll { it.javaClass.name.endsWith("ViewPager2") }
                        ?: root.findAll { it.javaClass.name.contains("ViewPager") }
                    val band = (indicator.height * scale).toInt()
                    vp?.let {
                        it.layoutParams = it.layoutParams.apply { height = parent.height - band }
                        it.requestLayout()
                        it.post { it.translationY = -it.top.toFloat() }
                    }
                    val bandCenter = parent.height - band / 2f
                    indicator.translationY = bandCenter - (indicator.top + indicator.height / 2f)
                }.onFailure { Utils.log("MainNav(native) position failed: $it") }
            }
        }.onFailure { Utils.log("MainNav(native) failed: $it") }
    }

    // Phone-style labeled nav metrics (Settings.mainNavLabels). Everything is DRIVEN BY 导航高度
    // (mainNavHeight) so the slider tunes the bar exactly like the watch strip — just with an M3
    // nav-bar active-indicator pill (wider than the icon) and a text label reserved below it.
    private class LabelMetrics(
        val iconSize: Int, val pillW: Int, val pillH: Int, val labelSp: Float, val barHeight: Int,
    )

    private fun labelMetrics(): LabelMetrics {
        val icon = Settings.mainNavHeight.value.toInt().coerceIn(8, 64)
        // Label scales with the icon so text stays proportional as the slider moves.
        val labelSp = (icon * 0.55f).coerceIn(10f, 14f)
        // Band = icon pill + a reserved block below for the label (2dp gap + ~1.35× text height + pad).
        val labelReserveDp = 6 + (labelSp * 1.35f).toInt()
        return LabelMetrics(
            iconSize = icon.dp,
            pillW = (icon + 20).dp,
            pillH = (icon + 8).dp,
            labelSp = labelSp,
            barHeight = (icon + 8).dp + labelReserveDp.dp,
        )
    }

    /** Nav band height: labeled (phone) bar reserves room for a label under the icon; else compact strip. */
    private fun barHeightPx(): Int =
        if (Settings.mainNavLabels.value) labelMetrics().barHeight
        else Settings.mainNavHeight.value.toInt().coerceIn(8, 64).dp + 6.dp

    /** Red unread badge; anchored top-right of the icon by the caller. */
    private fun makeBadge(ctx: android.content.Context) = TextView(ctx).apply {
        setTextColor(M3.onSurface)
        textSize = 8f
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = 9999f; setColor(BADGE_COLOR)
        }
        setPadding(3.dp, 0, 3.dp, 0)
        minWidth = 12.dp
        visibility = View.GONE
    }

    private fun buildCells(state: NavState) {
        val nav = state.nav
        val ctx = nav.context
        val barHeight = barHeightPx()

        state.cells.clear()
        nav.removeAllViews()

        if (Settings.mainNavLabels.value) {
            buildLabeledCells(state, barHeight)
            return
        }

        val square = Settings.mainNavSquare.value
        val iconSize = Settings.mainNavHeight.value.toInt().coerceIn(8, 64).dp
        val dotSize = (iconSize / 3).coerceAtLeast(4.dp)
        // WeChat-LiquidGlass active-indicator droplet pill geometry
        val pillW = (iconSize * 1.6f).toInt()
        val pillH = iconSize + 6.dp
        val cellW = pillW + 8.dp
        for (i in 0 until state.pageCount) {
            val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            // WeChat-LiquidGlass crystal active pill (10% translucent white droplet + 1dp frost stroke)
            val pill = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 9999f
                    setColor(0x28FFFFFF)
                    setStroke(1.dp, 0x4DFFFFFF)
                }
                visibility = View.GONE
            }
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                state.iconMap[i]?.let { setImageDrawable(MaterialSymbol(it, IDLE_ICON)) }
            }
            val dot = View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DOT_COLOR) }
            }
            val badge = makeBadge(ctx)
            val inner = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            inner.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            inner.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            inner.addView(dot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))
            inner.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, 12.dp, Gravity.TOP or Gravity.END))
            frame.addView(inner, FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER))

            val pos = i
            frame.isClickable = true
            M3.attachMiuixTouchFeedback(frame, 0.94f)
            frame.setOnClickListener { handleNavTap(state, pos) }

            // Fixed cell width (or weighted in square mode) so the pill never reflows the row.
            val lp = if (square) {
                LinearLayout.LayoutParams(0, barHeight, 1f)
            } else {
                LinearLayout.LayoutParams(cellW, barHeight).apply {
                    marginStart = 1.dp; marginEnd = 1.dp
                }
            }
            nav.addView(frame, lp)
            state.cells.add(Cell(frame, pill, icon, dot, badge).apply { iconColor = IDLE_ICON })
        }
    }

    /**
     * Phone-style labeled bottom nav: a standard bottom-navigation bar — each cell is an evenly
     * spread column of a modestly-sized icon (with an M3 active-indicator pill behind the selected
     * one) above the page's category name. All icons always show; the selected icon + label tint to
     * the accent. Icon size is fixed (phone-appropriate) rather than driven by 导航高度.
     */
    private fun buildLabeledCells(state: NavState, barHeight: Int) {
        val nav = state.nav
        val ctx = nav.context
        val m = labelMetrics()
        val iconSize = m.iconSize
        val pillW = m.pillW             // M3 nav-bar active indicator: wider than the icon
        val pillH = m.pillH
        for (i in 0 until state.pageCount) {
            val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            val pill = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 9999f
                    setColor(0x28FFFFFF)
                    setStroke(1.dp, 0x4DFFFFFF)
                }
                visibility = View.GONE
            }
            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                state.iconMap[i]?.let { setImageDrawable(MaterialSymbol(it, IDLE_ICON)) }
            }
            val badge = makeBadge(ctx)
            val label = TextView(ctx).apply {
                text = state.labelMap[i] ?: ""
                setTextColor(IDLE_ICON)
                textSize = m.labelSp
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
            }
            // Icon sits in a pill-sized frame; the badge hugs the icon's top-right (marginEnd pulls
            // it in from the wider pill's edge to the icon's edge).
            val iconWrap = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            iconWrap.addView(pill, FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER))
            iconWrap.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            iconWrap.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, 12.dp, Gravity.TOP or Gravity.END
            ).apply { marginEnd = (pillW - iconSize) / 2 })
            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                clipChildren = false; clipToPadding = false
            }
            column.addView(iconWrap, LinearLayout.LayoutParams(pillW, pillH))
            column.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2.dp })
            frame.addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

            val pos = i
            frame.isClickable = true
            M3.attachMiuixTouchFeedback(frame, 0.94f)
            frame.setOnClickListener { handleNavTap(state, pos) }
            // Evenly spread across the full width, like a phone bottom nav.
            nav.addView(frame, LinearLayout.LayoutParams(0, barHeight, 1f))
            // Unused in labeled mode (no dot state), but Cell requires a non-null dot view.
            val dot = View(ctx)
            state.cells.add(Cell(frame, pill, icon, dot, badge, label).apply { iconColor = IDLE_ICON })
        }
    }

    private fun updateNavLayoutParams(state: NavState) {
        val nav = state.nav
        val barHeight = barHeightPx()
        val square = Settings.mainNavSquare.value
        val labels = Settings.mainNavLabels.value
        val isSpread = square || labels
        val padH = if (square) 12.dp else 10.dp
        nav.setPadding(padH, 2.dp, padH, 2.dp)
        val parent = nav.parent as? ViewGroup ?: return
        val gap = if (isSpread) 4.dp else 12.dp
        val isBottom = Settings.bottomMainNav.value

        val dark = !Settings.lightMode.value
        (nav.background as? LiquidGlassDrawable)?.let { bg ->
            if (bg.dark != dark) {
                nav.background = LiquidGlassDrawable(dark)
            }
        }

        val parentW = parent.width
        val parentH = parent.height
        val targetWidth = if (isSpread) {
            if (parentW > 0) parentW - 24.dp else ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }

        nav.layoutParams?.let { lp ->
            lp.width = targetWidth
            lp.height = barHeight
            (lp as? ViewGroup.MarginLayoutParams)?.let { mlp ->
                mlp.leftMargin = 0
                mlp.rightMargin = 0
                mlp.topMargin = 0
                mlp.bottomMargin = 0
            }
        }

        if (parentH > 0) {
            val normalY = if (isBottom) {
                (parentH - parent.paddingBottom - barHeight - gap).toFloat() - nav.top.toFloat()
            } else {
                (parent.paddingTop + gap).toFloat() - nav.top.toFloat()
            }
            normalTranslationY = normalY
            if (!isNavHidden) {
                nav.translationY = normalY
            }
        }

        val nw = nav.width.takeIf { it > 0 } ?: nav.measuredWidth
        if (isSpread) {
            nav.translationX = 12.dp.toFloat() - nav.left.toFloat()
        } else if (parentW > 0 && nw > 0) {
            nav.translationX = ((parentW - nw) / 2f) - nav.left.toFloat()
        }

        nav.bringToFront()
        nav.requestLayout()
    }

    private var pillAnimator: ValueAnimator? = null

    private fun animatePillTo(bg: LiquidGlassDrawable, targetX: Float, animate: Boolean) {
        pillAnimator?.cancel()
        if (!animate || bg.pillCenterX < 0f || Math.abs(bg.pillCenterX - targetX) < 1f) {
            bg.pillCenterX = targetX
            bg.activeTabCenterX = targetX
            bg.invalidateSelf()
            return
        }
        val startX = bg.pillCenterX
        pillAnimator = ValueAnimator.ofFloat(startX, targetX).apply {
            duration = DUR_IN
            interpolator = EMPHASIZED
            addUpdateListener {
                val v = it.animatedValue as Float
                bg.pillCenterX = v
                bg.activeTabCenterX = v
                bg.invalidateSelf()
            }
            start()
        }
    }

    private fun render(state: NavState, force: Boolean = false) {
        // Rebuild the cells when a layout-affecting setting changed since they were built
        // (铺开 / 文字标签 / 显示所有图标 / 导航高度 / 位置) — apply immediately to layout & cells.
        val layoutKey = "${Settings.mainNavSquare.value}|${Settings.mainNavLabels.value}|" +
            "${Settings.mainNavAllIcons.value}|${Settings.mainNavHeight.value}|${Settings.bottomMainNav.value}"
        if (layoutKey != state.layoutKey || force) {
            state.layoutKey = layoutKey
            buildCells(state)
            updateNavLayoutParams(state)
            state.renderedKey = ""
            state.nav.requestLayout()
        }
        // Labeled (phone) mode is a standard bottom nav: always show every page's icon+label.
        val allIcons = Settings.mainNavAllIcons.value || Settings.mainNavLabels.value
        val showUnread = Settings.mainNavUnread.value
        state.current = runCatching { state.pager.current() }.getOrDefault(state.current)
        val counts = if (showUnread) (0 until state.pageCount).map { unreadFor(state, it) } else emptyList()
        val key = "${state.current}|$allIcons|$showUnread|$counts"
        if (!force && key == state.renderedKey) return
        val prevKey = state.renderedKey
        state.renderedKey = key
        // Animate only a pure page-selection change: not the first paint / a rebuild (force), and only
        // when everything but the selected index is unchanged (so settings + badge updates stay instant).
        val animate = !force && prevKey.isNotEmpty() && state.lastCurrent != state.current &&
            prevKey.substringAfter('|') == key.substringAfter('|')
        state.lastCurrent = state.current
        state.cells.forEachIndexed { i, cell ->
            val selected = i == state.current
            val hasIcon = state.iconMap[i] != null
            val showIcon = hasIcon && (allIcons || selected)
            applyCell(cell, selected, showIcon, animate)
            val count = if (showUnread) counts[i] else 0
            if (count > 0) {
                cell.badge.text = if (count > 99) "99+" else count.toString()
                cell.badge.visibility = View.VISIBLE
            } else {
                cell.badge.visibility = View.GONE
            }
        }
        val currentCell = state.cells.getOrNull(state.current)
        if (currentCell != null) {
            currentCell.frame.post {
                val cx = currentCell.frame.left + currentCell.frame.width / 2f
                (state.nav.background as? LiquidGlassDrawable)?.let { bg ->
                    val iconSize = Settings.mainNavHeight.value.toInt().coerceIn(8, 64).dp
                    bg.pillWidth = (iconSize * 1.6f)
                    bg.pillHeight = (iconSize + 6.dp).toFloat()
                    animatePillTo(bg, cx, animate)
                }
            }
        }
    }

    /** Apply a cell's selected/icon state, with M3 motion when [animate] (else instant). */
    private fun applyCell(cell: Cell, selected: Boolean, showIcon: Boolean, animate: Boolean) {
        // Labeled (phone) mode: the text label tints to the accent on the selected page (instant —
        // it tracks the icon-color crossfade closely enough without its own animator).
        cell.label?.setTextColor(if (selected) ACCENT else IDLE_ICON)
        // Icon ↔ dot crossfade (only matters in single-icon mode, where the non-selected pages show a dot).
        crossfade(cell.icon, showIcon, animate)
        crossfade(cell.dot, !showIcon, animate)

        // Pill is handled by the single gliding crystal pill in LiquidGlassDrawable
        cell.pill.visibility = View.GONE

        // Colors: icon tint crossfades idle↔accent (+ 1.1x focus scale matching WeChat-LiquidGlass)
        if (showIcon) {
            animateIconColor(cell, if (selected) ACCENT else IDLE_ICON, animate)
            val targetScale = if (selected) 1.1f else 1.0f
            if (animate) {
                cell.icon.animate().scaleX(targetScale).scaleY(targetScale)
                    .setDuration(DUR_IN).setInterpolator(EMPHASIZED).start()
            } else {
                cell.icon.scaleX = targetScale
                cell.icon.scaleY = targetScale
            }
        } else {
            (cell.dot.background as? GradientDrawable)?.setColor(if (selected) ACCENT else DOT_COLOR)
        }
    }

    /** Show/hide [v] with an alpha crossfade when [animate], else instantly. */
    private fun crossfade(v: View, show: Boolean, animate: Boolean) {
        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.visibility = View.VISIBLE
                if (animate) { v.alpha = 0f; v.animate().alpha(1f).setDuration(DUR_IN).start() } else v.alpha = 1f
            }
        } else if (v.visibility == View.VISIBLE) {
            if (animate) v.animate().alpha(0f).setDuration(DUR_OUT).withEndAction { v.visibility = View.GONE }.start()
            else { v.visibility = View.GONE; v.alpha = 1f }
        }
    }

    /** Crossfade [cell]'s icon tint to [target] (ARGB) when [animate], else set instantly. */
    private fun animateIconColor(cell: Cell, target: Int, animate: Boolean) {
        cell.colorAnim?.cancel()
        if (!animate || cell.iconColor == target) {
            cell.icon.setColorFilter(target); cell.iconColor = target; return
        }
        val anim = ValueAnimator.ofObject(ArgbEvaluator(), cell.iconColor, target).apply {
            duration = DUR_IN
            interpolator = EMPHASIZED
            addUpdateListener { cell.icon.setColorFilter(it.animatedValue as Int) }
        }
        cell.iconColor = target
        cell.colorAnim = anim
        anim.start()
    }

    /**
     * Nav cell tapped. When [Settings.mainNavUnreadJump] is on and the tap is on the page the user is
     * already viewing, jump to that page's pending item ([onRepeatTap]) instead of a no-op page
     * switch. Otherwise switch pages normally (resetting the chat-unread cursor for a fresh cycle).
     */
    private fun handleNavTap(state: NavState, pos: Int) {
        runCatching {
            val onThisPage = runCatching { state.pager.current() }.getOrDefault(state.current) == pos
            if (onThisPage && Settings.mainNavUnread.value && Settings.mainNavUnreadJump.value &&
                onRepeatTap(state, pos)
            ) return
            chatUnreadCursor = 0
            state.pager.setCurrent(pos)
        }.onFailure { Utils.log("MainNav tap($pos) failed: $it") }
    }

    /** Per-page repeat-tap action. Returns true when the tap was consumed (no page switch needed). */
    private fun onRepeatTap(state: NavState, pos: Int): Boolean = when (pos) {
        0 -> cycleChatUnread(state)
        1 -> openContactNotify()
        2 -> openQzoneNotify()
        else -> false
    }

    /**
     * Messages page: scroll the next unread conversation to the top of the chat list, cycling back to
     * the first after the last. Reads the live [ChatsListAdapter] items (RecentContactChatItem) and
     * skips muted/DND chats — same filter the unread badge uses ([messagesUnread]).
     */
    private fun cycleChatUnread(state: NavState): Boolean {
        val pagerView = state.pager.pager as? ViewGroup ?: return false
        val ctx = pagerView.context
        val rvId = ctx.resources.getIdentifier("chat_list", "id", ctx.packageName)
        val rv = (if (rvId != 0) pagerView.findAll { it is RecyclerView && it.id == rvId } else null)
            as? RecyclerView ?: run { Utils.log("MainNav: chat_list RV not found"); return false }
        val adapter = rv.adapter ?: return false
        val getItem = runCatching {
            adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
        }.getOrNull() ?: run { Utils.log("MainNav: adapter.getItem not found"); return false }

        val positions = ArrayList<Int>()
        for (i in 0 until adapter.itemCount) {
            val item = runCatching { getItem.invoke(adapter, i) }.getOrNull()
            if (item is RecentContactChatItem &&
                item.a.unreadCnt > 0 && !RecentContacts.isMuted(item.p, item.q)
            ) positions.add(i)
        }
        if (positions.isEmpty()) { chatUnreadCursor = 0; return true }

        if (chatUnreadCursor >= positions.size) chatUnreadCursor = 0
        val target = positions[chatUnreadCursor]
        chatUnreadCursor = (chatUnreadCursor + 1) % positions.size
        rv.stopScroll()
        rv.smoothScrollToStart(target)
        Utils.log("MainNav: chat unread jump pos=$target (cursor=$chatUnreadCursor/${positions.size})")
        return true
    }

    /**
     * Contacts page: open the notification screen that actually has a pending count (好友通知 first,
     * else 群通知). Delegates to the live fragment's copied-in methods via reflection — the @Mixin
     * class type doesn't exist at runtime, so we can't reference it directly.
     */
    private fun openContactNotify(): Boolean {
        val f = contactFragment?.get() ?: return false
        val method = when {
            contactFriendUnread > 0 -> "topFriendNotify"
            contactGroupUnread > 0 -> "topGroupNotify"
            else -> {
                // Nothing pending — jump the contacts list to the top instead of doing nothing.
                Utils.log("MainNav: contacts no unread → scroll to top")
                scrollToTop(findRecycler((f as? androidx.fragment.app.Fragment)?.view))
                return true
            }
        }
        return runCatching { f.javaClass.getMethod(method).invoke(f); true }
            .onFailure { Utils.log("MainNav: openContactNotify failed: $it") }
            .getOrDefault(true)
    }

    /**
     * QZone page: open the 通知 screen only when there's an active notification (the page's QUIBadge
     * `unread` field `k`, set VISIBLE in QZoneMainFrame.f0() iff the undeal count > 0); otherwise jump
     * the feed (SmartRefreshLayout `n` → RecyclerView) to the top.
     */
    private fun openQzoneNotify(): Boolean {
        val f = qzoneFragment?.get() ?: return false
        val hasNotify = runCatching {
            (f.javaClass.getField("k").get(f) as? View)?.visibility == View.VISIBLE
        }.getOrDefault(false)
        if (hasNotify) {
            return runCatching { f.javaClass.getMethod("barNotify").invoke(f); true }
                .onFailure { Utils.log("MainNav: openQzoneNotify failed: $it") }
                .getOrDefault(true)
        }
        // No active notification — scroll the feed to the top instead of opening 通知.
        Utils.log("MainNav: qzone no notify → scroll to top")
        runCatching {
            scrollToTop(findRecycler(f.javaClass.getField("n").get(f) as? View))
        }.onFailure { Utils.log("MainNav: qzone scroll-top failed: $it") }
        return true
    }

    /** The first RecyclerView at or under [v] (the page root may itself be the list). */
    private fun findRecycler(v: View?): RecyclerView? =
        v as? RecyclerView ?: (v as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView

    private fun scrollToTop(rv: RecyclerView?) {
        rv ?: return
        rv.stopScroll()
        rv.smoothScrollToStart(0)
    }

    /** Ensure page list content extends behind the floating bar without the last row being occluded. */
    private fun ensurePagePadding(pagerView: View, bottomPad: Int) {
        runCatching {
            val vp = pagerView as? androidx.viewpager2.widget.ViewPager2 ?: return
            val rv = vp.getChildAt(0) as? RecyclerView ?: return
            for (i in 0 until rv.childCount) {
                val pageView = rv.getChildAt(i)
                val pageRv = findRecycler(pageView)
                if (pageRv != null) {
                    if (pageRv.clipToPadding) pageRv.clipToPadding = false
                    if (pageRv.paddingBottom < bottomPad) {
                        pageRv.setPadding(pageRv.paddingLeft, pageRv.paddingTop, pageRv.paddingRight, bottomPad)
                    }
                    if (pageRv.getTag(NAV_TAG.hashCode()) == null) {
                        pageRv.setTag(NAV_TAG.hashCode(), true)
                        pageRv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                val state = active ?: return
                                if (!Settings.mainNavAutoHide.value) {
                                    if (isNavHidden) showNav(state.nav)
                                    return
                                }
                                if (Math.abs(dy) > 5) {
                                    if (dy > 0) {
                                        hideNav(state.nav)
                                    } else {
                                        showNav(state.nav)
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    /**
     * Position the floating liquid-glass capsule above the page content.
     * ViewPager2 is stretched to 100% full-bleed so content extends completely to the bottom edge.
     */
    private fun positionBar(
        parent: ViewGroup, pagerView: View, indicator: View, nav: LinearLayout, barHeight: Int,
    ) {
        var lastH = -1
        var lastW = -1
        var lastTop = Int.MIN_VALUE
        var lastNavW = -1
        var lastNavLeft = Int.MIN_VALUE
        nav.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val h = parent.height
                    val w = parent.width
                    val top = pagerView.top
                    val nw = nav.width.takeIf { it > 0 } ?: nav.measuredWidth
                    val navLeft = nav.left
                    val isParentVisible = parent.isShown && parent.visibility == View.VISIBLE && pagerView.isShown
                    if (!isParentVisible) {
                        if (nav.visibility != View.GONE) nav.visibility = View.GONE
                        return true
                    }
                    if (h <= 0 || w <= 0) return true
                    if (h == lastH && w == lastW && top == lastTop && nw == lastNavW && navLeft == lastNavLeft) {
                        if (!isNavHidden && nav.visibility != View.VISIBLE) nav.visibility = View.VISIBLE
                        BlurEngine.capture(nav, pagerView)
                        return true
                    }
                    lastH = h; lastW = w; lastTop = top; lastNavW = nw; lastNavLeft = navLeft
                    runCatching {
                        // 1. Keep content pager full-bleed so content extends completely to the bottom edge
                        if (pagerView.layoutParams.height != h) {
                            pagerView.layoutParams = pagerView.layoutParams.apply { height = h }
                            pagerView.requestLayout()
                        }
                        pagerView.translationY = -top.toFloat()

                        // 2. Reposition & center floating capsule via native constraints/layout params
                        active?.let { updateNavLayoutParams(it) }

                        nav.bringToFront()
                        nav.visibility = View.VISIBLE
                        val gap = if (Settings.mainNavSquare.value || Settings.mainNavLabels.value) 4.dp else 12.dp
                        ensurePagePadding(pagerView, barHeight + gap + 10.dp)
                        BlurEngine.capture(nav, pagerView)
                    }.onFailure {
                        nav.visibility = View.VISIBLE
                    }
                    BlurEngine.capture(nav, pagerView)
                    return true
                }
            }
        )
    }

    // Fixed page order: 0=chat, 1=contacts(person), 2=qzone(star), 3=self(settings)
    private val PAGE_ICONS = listOf(
        MaterialSymbols.chat_bubble,
        MaterialSymbols.person,
        MaterialSymbols.star,
        MaterialSymbols.settings,
    )

    // Category names for the labeled (phone) nav, same order as PAGE_ICONS.
    private val PAGE_LABELS = listOf("消息", "联系人", "动态", "我")

    private fun materialIconMapOf(pageCount: Int): Map<Int, String> = buildMap {
        for (i in 0 until minOf(pageCount, PAGE_ICONS.size)) put(i, PAGE_ICONS[i])
    }

    private fun materialLabelMapOf(pageCount: Int): Map<Int, String> = buildMap {
        for (i in 0 until minOf(pageCount, PAGE_LABELS.size)) put(i, PAGE_LABELS[i])
    }

    /**
     * Reflection wrapper over the live content pager, which may be a v1 androidx ViewPager or a
     * ViewPager2 — both expose getAdapter/getCurrentItem/setCurrentItem with the same signatures.
     */
    class PagerCtl(val pager: View) {
        fun count(): Int = runCatching {
            val adapter = pager.javaClass.getMethod("getAdapter").invoke(pager) ?: return 0
            runCatching { adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int }
                .getOrElse { adapter.javaClass.getMethod("getCount").invoke(adapter) as Int }
        }.getOrDefault(0)

        fun current(): Int = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as Int
        }.getOrDefault(0)

        fun setCurrent(i: Int) {
            // The app's ViewPager2 is R8-minified: setCurrentItem(int,boolean) may be stripped, so
            // fall back to setCurrentItem(int) (same approach as the 屏蔽返回键 hook).
            runCatching {
                pager.javaClass.getMethod(
                    "setCurrentItem", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                ).invoke(pager, i, true)
            }.recoverCatching {
                pager.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType).invoke(pager, i)
            }.onFailure { Utils.log("MainNav setCurrent($i) failed: $it") }
        }

        fun beginFakeDrag(): Boolean = runCatching {
            pager.javaClass.getMethod("beginFakeDrag").invoke(pager) as Boolean
        }.getOrDefault(false)

        fun fakeDragBy(offsetPx: Float): Boolean = runCatching {
            pager.javaClass.getMethod("fakeDragBy", Float::class.javaPrimitiveType).invoke(pager, offsetPx) as? Boolean ?: true
        }.getOrDefault(false)

        fun endFakeDrag(): Boolean = runCatching {
            pager.javaClass.getMethod("endFakeDrag").invoke(pager) as? Boolean ?: true
        }.getOrDefault(false)

        fun isFakeDragging(): Boolean = runCatching {
            pager.javaClass.getMethod("isFakeDragging").invoke(pager) as Boolean
        }.getOrDefault(false)

        // registerOnPageChangeCallback is also stripped, so we watch page changes via the framework
        // OnScrollChangedListener (fires on any descendant scroll, incl. page swipes) and re-read
        // getCurrentItem(), which is NOT minified. Cheap: render only acts when the page changed.
        private var scrollListener: android.view.ViewTreeObserver.OnScrollChangedListener? = null

        fun observe(onChange: () -> Unit) {
            val l = android.view.ViewTreeObserver.OnScrollChangedListener { onChange() }
            scrollListener = l
            runCatching { pager.viewTreeObserver.addOnScrollChangedListener(l) }
        }

        fun removeListener() {
            scrollListener?.let { l -> runCatching { pager.viewTreeObserver.removeOnScrollChangedListener(l) } }
            scrollListener = null
        }
    }
}
