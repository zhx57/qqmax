package momoi.mod.qqpro.hook

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
    // Liquid glass crystal palette: selected icon pure white, inactive frosted white, glowing pill
    private val ACCENT get() = 0xFFFFFFFF.toInt()
    private val PILL_COLOR get() = 0x50FFFFFF
    private val IDLE_ICON get() = 0x99FFFFFF.toInt()
    private val DOT_COLOR get() = 0x80FFFFFF.toInt()

    /**
     * Liquid-glass bar surface, 1:1 fidelity ported from big-dictator/LiquidTab (config/GlassConfig defaults +
     * GlassHostLayout.drawGlassPanel + NativeBackdropBlur).
     *
     * Features:
     *  - Ambient Drop Shadow: panel shadow ringing the capsule on all sides (clipOutPath prevents inner dimming).
     *  - Backdrop with Saturation Boost: 1.45x color saturation filter on the captured/downscaled scene.
     *  - Surface fill: LiquidTab surfaceContainerColor — dark #211F26 / light #FFFFFF at fixed 40% alpha.
     *  - Interactive Highlight: LiquidTab RadialGradient bloom centered at the currently active tab.
     *  - Dual-light bloom stroke: 1dp white edge lit by two lights (-45° primary 1.0, lower secondary 0.4).
     *  - Capsule geometry: corner radius = height × 50% (cornerRadiusPercent = 50).
     */
    private class LiquidGlassDrawable(private val dark: Boolean) : android.graphics.drawable.Drawable() {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glassGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.dp.toFloat()
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 0, 0, 0)
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val shadowPath = Path()
        private val rect = RectF()
        private val shadowRect = RectF()

        /** Center X coordinate of the active tab for rendering the interactive highlight bloom. */
        var activeTabCenterX: Float = -1f

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            if (bounds.isEmpty) return

            // Luminous crystal glass frosted sheen
            glassGlossPaint.shader = android.graphics.LinearGradient(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.left.toFloat(), bounds.bottom.toFloat(),
                intArrayOf(
                    Color.argb(85, 255, 255, 255), // top icy specular sheen
                    Color.argb(30, 255, 255, 255), // body
                    Color.argb(15, 255, 255, 255),
                    Color.argb(50, 255, 255, 255), // bottom rim reflection
                ),
                floatArrayOf(0f, 0.35f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )

            // Deep acrylic glass substrate
            bgPaint.color = if (dark) Color.argb(110, 26, 26, 34) else Color.argb(130, 240, 240, 248)

            // Dual-light crisp edge outline
            val steps = 90
            val strokeColors = IntArray(steps)
            val baseAlpha = if (dark) 0.85f else 0.95f
            for (i in 0 until steps) {
                val theta = Math.toRadians((i * 360.0 / steps))
                val l1 = Math.cos(theta + Math.toRadians(45.0)).toFloat()
                val l2 = Math.cos(theta - Math.toRadians(90.0)).toFloat()
                val brightness = (0.25f + 0.75f * (l1 * l1) + 0.45f * (l2 * l2)).coerceIn(0f, 1f)
                val alpha = (brightness * baseAlpha * 255).toInt().coerceIn(0, 255)
                strokeColors[i] = Color.argb(alpha, 255, 255, 255)
            }
            strokePaint.shader = SweepGradient(bounds.exactCenterX(), bounds.exactCenterY(), strokeColors, null)

            // Top curved specular bevel reflection
            specularPaint.shader = android.graphics.LinearGradient(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.left.toFloat(), bounds.top + bounds.height() * 0.45f,
                Color.argb(130, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty) return
            rect.set(b)
            val r = rect.height().coerceAtMost(rect.width()) / 2f

            // 1. Soft 3D drop shadow underneath
            shadowRect.set(rect.left + 1.dp, rect.top + 3.dp, rect.right - 1.dp, rect.bottom + 5.dp)
            shadowPath.reset()
            val sr = shadowRect.height().coerceAtMost(shadowRect.width()) / 2f
            shadowPath.addRoundRect(shadowRect, sr, sr, Path.Direction.CW)
            canvas.drawPath(shadowPath, shadowPaint)

            path.reset()
            path.addRoundRect(rect, r, r, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(path)

            // 2. Base acrylic glass body
            canvas.drawRect(rect, bgPaint)

            // 3. Crystal frosted glass shine
            canvas.drawRect(rect, glassGlossPaint)

            // 4. Upper bevel glass reflection
            canvas.drawRect(rect, specularPaint)

            // 5. Active tab interactive glow
            if (activeTabCenterX >= 0f) {
                haloPaint.shader = RadialGradient(
                    activeTabCenterX,
                    rect.centerY(),
                    rect.height() * 1.3f,
                    Color.argb(110, 75, 150, 255),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect, haloPaint)
            }
            canvas.restore()

            // 6. Dual-light crisp edge outline
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

            val nav = LinearLayout(parent.context).apply {
                tag = NAV_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                // Liquid-glass surface (LiquidTab recipe): 40% fill + dual-light bloom stroke.
                background = LiquidGlassDrawable(!Settings.lightMode.value)
                val padH = if (Settings.mainNavSquare.value) 10.dp else 8.dp
                setPadding(padH, 2.dp, padH, 2.dp)
                // Stay hidden until positionBar has placed us (bottom/top); otherwise the bar paints
                // one frame at its default y=0 (top) before the deferred post moves it down — a visible
                // flash at the top on every return to the home page in bottom mode.
                visibility = View.INVISIBLE
            }
            val state = NavState(nav, pager, iconMap, labelMap, pageCount)
            state.current = pager.current()
            active = state

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
                nav.elevation = 20.dp.toFloat()
                nav.translationZ = 20.dp.toFloat()
            }
            (nav.layoutParams as? ViewGroup.MarginLayoutParams)?.let { mlp ->
                if (isSpread) {
                    mlp.leftMargin = 12.dp
                    mlp.rightMargin = 12.dp
                }
            }

            pager.observe {
                render(state)
                ensurePagePadding(pagerView, barHeightPx() + 14.dp)
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
        // M3 active-indicator pill. Kept modest so the bar stays compact on a round screen, and the
        // cell is sized to the pill so toggling it never reflows the row (icons must not shift).
        val pillW = (iconSize * 1.5f).toInt()
        val pillH = iconSize + 4.dp
        val cellW = pillW + 4.dp
        for (i in 0 until state.pageCount) {
            val frame = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            // Liquid glass crystal active pill
            val pill = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 9999f
                    setColor(Color.argb(85, 255, 255, 255))
                    setStroke(1.dp, Color.argb(190, 255, 255, 255))
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
            // Inner container sized to the icon and centered in the cell. The badge anchors to the
            // inner's top-right so it hugs the icon even when the cell is stretched (square mode).
            val inner = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            val innerW = iconSize + 6.dp
            inner.addView(pill, FrameLayout.LayoutParams(pillW, pillH, Gravity.CENTER))
            inner.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            inner.addView(dot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER))
            inner.addView(badge, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, 12.dp, Gravity.TOP or Gravity.END))
            frame.addView(inner, FrameLayout.LayoutParams(innerW, barHeight, Gravity.CENTER))

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
                    setColor(Color.argb(85, 255, 255, 255))
                    setStroke(1.dp, Color.argb(190, 255, 255, 255))
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
        val padH = if (square) 12.dp else 8.dp
        nav.setPadding(padH, 2.dp, padH, 2.dp)
        val parent = nav.parent as? ViewGroup
        val parentW = parent?.width?.takeIf { it > 0 } ?: state.pager.pager.width
        val targetWidth = if (isSpread) {
            if (parentW > 0) parentW - 24.dp else ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        nav.layoutParams?.let { lp ->
            lp.width = targetWidth
            lp.height = barHeight
            (lp as? ViewGroup.MarginLayoutParams)?.let { mlp ->
                if (isSpread) {
                    mlp.leftMargin = 12.dp
                    mlp.rightMargin = 12.dp
                } else {
                    mlp.leftMargin = 0
                    mlp.rightMargin = 0
                }
            }
        }
        if (parent != null && parent.height > 0) {
            val gap = if (isSpread) 4.dp else 10.dp
            if (Settings.bottomMainNav.value) {
                nav.translationY = (parent.height - barHeight - gap).toFloat()
            } else {
                nav.translationY = gap.toFloat()
            }
            if (!isSpread) {
                val nw = nav.measuredWidth.takeIf { it > 0 } ?: nav.width
                if (nw > 0 && parent.width > nw) {
                    nav.translationX = ((parent.width - nw) / 2f) - nav.left.toFloat()
                }
            } else {
                nav.translationX = 0f
            }
        }
        nav.bringToFront()
        nav.requestLayout()
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
                    bg.activeTabCenterX = cx
                    bg.invalidateSelf()
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

        // M3 active indicator: the pill scales + fades in behind the selected icon, out otherwise.
        val pillShown = selected && showIcon
        if (pillShown) {
            if (cell.pill.visibility != View.VISIBLE || cell.pill.alpha < 1f) {
                cell.pill.visibility = View.VISIBLE
                if (animate) {
                    cell.pill.alpha = 0f; cell.pill.scaleX = 0.55f; cell.pill.scaleY = 0.85f
                    cell.pill.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(DUR_IN).setInterpolator(EMPHASIZED).start()
                } else {
                    cell.pill.alpha = 1f; cell.pill.scaleX = 1f; cell.pill.scaleY = 1f
                }
            }
        } else if (cell.pill.visibility == View.VISIBLE) {
            if (animate) {
                cell.pill.animate().alpha(0f).scaleX(0.55f).scaleY(0.85f)
                    .setDuration(DUR_OUT).setInterpolator(EMPHASIZED)
                    .withEndAction { cell.pill.visibility = View.GONE }.start()
            } else {
                cell.pill.visibility = View.GONE
                cell.pill.alpha = 1f; cell.pill.scaleX = 1f; cell.pill.scaleY = 1f
            }
        }

        // Colors: icon tint crossfades idle↔accent (+ a small scale pop when newly selected); the dot
        // (single-icon mode) is set instantly.
        if (showIcon) {
            animateIconColor(cell, if (selected) ACCENT else IDLE_ICON, animate)
            if (selected && animate) {
                cell.icon.scaleX = 0.8f; cell.icon.scaleY = 0.8f
                cell.icon.animate().scaleX(1f).scaleY(1f).setDuration(DUR_IN).setInterpolator(EMPHASIZED).start()
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
        nav.viewTreeObserver.addOnPreDrawListener(
            object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val h = parent.height
                    val w = parent.width
                    val top = pagerView.top
                    if (h <= 0 || w <= 0) return true
                    if (h == lastH && w == lastW && top == lastTop) return true
                    lastH = h; lastW = w; lastTop = top
                    runCatching {
                        // 1. Keep content pager full-bleed so content extends completely to the bottom edge
                        if (pagerView.layoutParams.height != h) {
                            pagerView.layoutParams = pagerView.layoutParams.apply { height = h }
                            pagerView.requestLayout()
                        }
                        pagerView.translationY = -top.toFloat()

                        // 2. Float the bar above the bottom with a clean gap
                        val square = Settings.mainNavSquare.value
                        val labels = Settings.mainNavLabels.value
                        val isSpread = square || labels
                        val gap = if (isSpread) 4.dp else 10.dp
                        if (Settings.bottomMainNav.value) {
                            nav.translationY = (h - barHeight - gap).toFloat()
                        } else {
                            nav.translationY = gap.toFloat()
                        }

                        // 3. Center horizontally for wrap_content capsule island
                        if (!isSpread) {
                            val nw = nav.measuredWidth.takeIf { it > 0 } ?: nav.width
                            if (nw > 0 && w > nw) {
                                nav.translationX = ((w - nw) / 2f) - nav.left.toFloat()
                            }
                        } else {
                            nav.translationX = 0f
                        }

                        nav.bringToFront()
                        nav.visibility = View.VISIBLE
                        ensurePagePadding(pagerView, barHeight + gap + 10.dp)
                    }.onFailure {
                        nav.visibility = View.VISIBLE
                    }
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
