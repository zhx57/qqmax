package momoi.mod.qqpro.lib.material

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * Miuix (HyperOS / 澎湃OS) design tokens for QQMax's Kotlin-DSL UI (the project uses no XML).
 *
 * Every value below is taken verbatim from the upstream Miuix design system (compose-miuix-ui,
 * stable v0.9.4): `theme/Colors.kt` (lightColorScheme/darkColorScheme), `theme/TextStyles.kt`
 * (type scale), and the component Defaults (Card 16dp radius, Button 58x40/16dp, Switch 49x28
 * track + 20dp thumb, Checkbox 26dp, BasicComponent 56dp min height / 16dp inset, Dialog 32dp
 * radius / 24dp inset, SmallTitle 28x8 padding). DO NOT invent values — when a token is missing,
 * look it up in the Miuix sources instead of guessing.
 *
 * Centralized here — not inlined per feature — so every screen shares one palette and one set of
 * widgets. This is the SINGLE SOURCE OF TRUTH for app colors: the legacy [momoi.mod.qqpro.Colors]
 * object aliases these tokens, so retheming = editing the token values here. Tokens are named by
 * Miuix role. All classes are public on purpose (a @Mixin body that references a helper needs it
 * public, or it hits IllegalAccessError at runtime).
 */
object M3 {
    // ── Color tokens (all user-overridable) ──────────────────────────────────────
    // Every role below resolves LIVE from its own Settings color pref (a hex string). A blank
    // pref falls back to the built-in default (and for system-derived tokens, to the Material You
    // value when 跟随系统主题色 is on). The single source of truth is here.
    // Whether to use the light baseline palette instead of the dark one (Settings.lightMode). Drives
    // each token's DEFAULT below; an explicit 外观主题 override still wins when set.
    private val light get() = momoi.mod.qqpro.Settings.lightMode.value
    /** Pick the dark- or light-mode baseline default for the current [light] mode. */
    private fun dl(darkValue: Int, lightValue: Int) = if (light) lightValue else darkValue

    // ── System (Material You) color resolution ───────────────────────────────────
    // The roles a system-color palette can supply. Each token below resolves via [tok]/[sys]:
    // an explicit 外观主题 override wins; else the system value (when 跟随系统主题色 is on and the
    // device exposes Material You); else the built-in default.
    enum class SysRole {
        PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER,
        SURFACE, SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH, SURFACE_VARIANT,
        ON_SURFACE, ON_SURFACE_VARIANT, ON_SURFACE_TIP, HINT, OUTLINE, OUTLINE_VARIANT,
    }

    /** System dynamic color for [role], or null when 跟随系统主题色 is off (or the device has none). */
    private fun sys(role: SysRole): Int? =
        if (momoi.mod.qqpro.Settings.followSystemTheme.value) SystemPalette.color(role) else null

    /** Token resolver: explicit hex override → system dynamic color → built-in [default]. */
    private fun tok(prefValue: String?, role: SysRole, default: Int): Int =
        parseColorOrNull(prefValue) ?: sys(role) ?: default

    /**
     * Material You (Android 12+ / API 31+) dynamic color source. Reads the system's wallpaper-derived
     * tonal palettes (`android:color/system_accent1_*`, `system_neutral1/2_*`) by resource NAME — the
     * project's compileSdk predates those symbols, but the resources exist at RUNTIME on any API 31+
     * device regardless of targetSdk (28 here). [available] is false on older devices (getIdentifier
     * returns 0), so callers fall back to the built-in palette. Public per the @Mixin helper rule.
     *
     * Android's `system_accentN_X` tones run inverted vs MD tone (X=0 → tone 100/white, X=1000 →
     * tone 0/black), so e.g. tone-80 primary = `_200`. Surface-container steps aren't exposed as
     * discrete tones, so they're derived from surface by an elevation-style blend ([surfaceTint]).
     */
    object SystemPalette {
        private val res get() = momoi.mod.qqpro.util.Utils.application.resources
        private val cache = HashMap<String, Int?>()

        /** Whether this device exposes the Material You dynamic palette at all (read once). */
        val available: Boolean by lazy {
            android.os.Build.VERSION.SDK_INT >= 31 && tone("system_accent1_500") != null
        }

        /** Read one android system color tone by name (e.g. "system_accent1_200"); null if absent. */
        private fun tone(name: String): Int? {
            if (cache.containsKey(name)) return cache[name]
            val id = res.getIdentifier(name, "color", "android")
            val c = if (id == 0) null else runCatching { res.getColor(id, null) }.getOrNull()
            cache[name] = c
            return c
        }

        /** Derive a surface-container tone from surface: blend toward white ([amount]>0) or black (<0). */
        private fun surfaceTint(amount: Float): Int? {
            val base = color(SysRole.SURFACE) ?: return null
            return if (amount >= 0) blend(base, 0xFF_FFFFFF.toInt(), amount)
            else blend(base, 0xFF_000000.toInt(), -amount)
        }

        /** Map a [role] onto the system tonal palette for the current light/dark mode. */
        fun color(role: SysRole): Int? {
            if (!available) return null
            val dark = !light
            return when (role) {
                SysRole.PRIMARY                -> tone(if (dark) "system_accent1_200" else "system_accent1_600")
                SysRole.ON_PRIMARY             -> tone(if (dark) "system_accent1_800" else "system_accent1_0")
                SysRole.PRIMARY_CONTAINER      -> tone(if (dark) "system_accent1_700" else "system_accent1_100")
                SysRole.ON_PRIMARY_CONTAINER   -> tone(if (dark) "system_accent1_100" else "system_accent1_900")
                SysRole.SURFACE                -> tone(if (dark) "system_neutral1_900" else "system_neutral1_10")
                SysRole.SURFACE_CONTAINER      -> surfaceTint(if (dark) 0.05f else -0.03f)
                SysRole.SURFACE_CONTAINER_HIGH -> surfaceTint(if (dark) 0.09f else -0.06f)
                SysRole.SURFACE_VARIANT        -> tone(if (dark) "system_neutral2_700" else "system_neutral2_100")
                SysRole.ON_SURFACE             -> tone(if (dark) "system_neutral1_100" else "system_neutral1_900")
                SysRole.ON_SURFACE_VARIANT     -> tone(if (dark) "system_neutral2_200" else "system_neutral2_700")
                SysRole.ON_SURFACE_TIP         -> tone(if (dark) "system_neutral2_300" else "system_neutral2_600")
                SysRole.HINT                   -> tone(if (dark) "system_neutral2_400" else "system_neutral2_500")
                SysRole.OUTLINE                -> tone(if (dark) "system_neutral2_400" else "system_neutral2_500")
                SysRole.OUTLINE_VARIANT        -> tone(if (dark) "system_neutral2_700" else "system_neutral2_200")
            }
        }
    }

    // Accent (primary). Miuix lightColorScheme/darkColorScheme: #3482FF (light) / #277AF7 (dark).
    val DEFAULT_PRIMARY get() = dl(0xFF_277AF7.toInt(), 0xFF_3482FF.toInt())

    val primary: Int get() = tok(momoi.mod.qqpro.Settings.themeColor.value, SysRole.PRIMARY, DEFAULT_PRIMARY)
    val primaryVariant: Int get() = dl(0xFF_0073DD.toInt(), 0xFF_3482FF.toInt())
    /**
     * Label/icon color on a filled primary surface (Miuix onPrimary = white in both modes). A blank
     * pref resolves to white; only a very LIGHT accent (e.g. pale yellow) flips to a dark tone for
     * contrast. Avoids the near-black text a blanket darken() produced on mid-bright accents.
     */
    val onPrimary: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themeOnPrimary.value)
        ?: sys(SysRole.ON_PRIMARY)
        ?: if (luminance(primary) > 0.72f) darken(primary, 0.78f) else 0xFF_FFFFFF.toInt()
    /** Miuix primaryContainer: #5D9BFF (light) / #338FE4 (dark) — opaque accent container. */
    val primaryContainer: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themePrimaryContainer.value)
        ?: sys(SysRole.PRIMARY_CONTAINER)
        ?: dl(0xFF_338FE4.toInt(), 0xFF_5D9BFF.toInt())
    /** Miuix onPrimaryContainer: white in both modes. */
    val onPrimaryContainer: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themeOnPrimaryContainer.value)
        ?: sys(SysRole.ON_PRIMARY_CONTAINER)
        ?: 0xFF_FFFFFF.toInt()
    /**
     * OPAQUE version of [primaryContainer] (which is opaque itself in Miuix). Use where a fill is
     * rasterized with no live background behind it — e.g. an avatar/icon drawable.
     */
    val tonalSolid: Int get() = compositeOver(primaryContainer, surface)

    // ── Secondary control family (Miuix Switch/Checkbox/Button off-state + normal button) ──
    /** Miuix secondary: #E6E6E6 (light) / #505050 (dark) — unchecked switch track, unchecked checkbox fill. */
    val secondary: Int get() = dl(0xFF_505050.toInt(), 0xFF_E6E6E6.toInt())
    /** Miuix onSecondary: white — the unchecked switch THUMB sits on [secondary]. */
    val onSecondary: Int get() = 0xFF_FFFFFF.toInt()
    /** Miuix secondaryVariant: #F0F0F0 (light) / #434343 (dark) — normal button / raised fill. */
    val secondaryVariant: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themeSecondary.value)
        ?: dl(0xFF_434343.toInt(), 0xFF_F0F0F0.toInt())
    /** Miuix onSecondaryVariant: #303030 (light) / #D9D9D9 (dark) — normal button label. */
    val onSecondaryVariant: Int get() = dl(0xFF_D9D9D9.toInt(), 0xFF_303030.toInt())
    /** Miuix secondaryContainer: #F0F0F0 (light) / #434343 (dark) — TextField input fill. */
    val inputFill: Int get() = dl(0xFF_434343.toInt(), 0xFF_F0F0F0.toInt())
    /** Miuix onSecondaryContainer: #A9A9A9 (light) / #7C7C7C (dark) — input label/hint. */
    val onInputFill: Int get() = dl(0xFF_7C7C7C.toInt(), 0xFF_A9A9A9.toInt())
    /** Miuix tertiaryContainer: #EAF2FF (light) / #2B3B54 (dark) — dropdown selected container. */
    val tertiaryContainer: Int get() = dl(0xFF_2B3B54.toInt(), 0xFF_EAF2FF.toInt())
    /** Miuix onTertiaryContainer: #3482FF (light) / #4788FF (dark) — dropdown selected content. */
    val onTertiaryContainer: Int get() = dl(0xFF_4788FF.toInt(), 0xFF_3482FF.toInt())

    // ── Surfaces (Miuix background/surface/surfaceContainer semantics) ──────────
    // Miuix: surface = page canvas & chrome (#F7F7F7 light / Black dark — the HyperOS look);
    // background = dialog card fill (white / #242424); surfaceContainer = Card fill
    // (white / #242424 — floats above the canvas). [surface] here is the page canvas, mapping
    // to Miuix `surface`; dialogs/floats use [dialogBackground] (Miuix `background`).
    val surface: Int get() = tok(momoi.mod.qqpro.Settings.themeSurface.value, SysRole.SURFACE, dl(0xFF_000000.toInt(), 0xFF_F7F7F7.toInt()))          // page canvas (Miuix surface)
    /** Miuix background: white (light) / #242424 (dark) — dialog & overlay fill. */
    val dialogBackground: Int get() = dl(0xFF_242424.toInt(), 0xFF_FFFFFF.toInt())
    val surfaceFloat: Int get() = surface                                        // legacy alias, same role
    val surfaceContainer: Int get() = tok(momoi.mod.qqpro.Settings.themeSurfaceContainer.value, SysRole.SURFACE_CONTAINER, dl(0xFF_242424.toInt(), 0xFF_FFFFFF.toInt()))     // card container
    val surfaceContainerHigh: Int get() = tok(momoi.mod.qqpro.Settings.themeSurfaceContainerHigh.value, SysRole.SURFACE_CONTAINER_HIGH, dl(0xFF_434343.toInt(), 0xFF_F0F0F0.toInt())) // raised card / secondary button fill
    val surfaceContainerHighest: Int get() = dl(0xFF_2D2D2D.toInt(), 0xFF_E8E8E8.toInt())
    val surfaceVariant: Int get() = tok(momoi.mod.qqpro.Settings.themeSurfaceVariant.value, SysRole.SURFACE_VARIANT, dl(0xFF_242424.toInt(), 0xFF_FFFFFF.toInt()))

    // ── Content (Miuix typography tones) ───────────────────────
    val onSurface: Int get() = tok(momoi.mod.qqpro.Settings.themeOnSurface.value, SysRole.ON_SURFACE, dl(0xFF_F2F2F2.toInt(), 0xFF_000000.toInt()))
    /** Miuix onSurfaceVariantSummary: #99000000 (light) / #80FFFFFF (dark) — row summaries. */
    val onSurfaceVariant: Int get() = tok(momoi.mod.qqpro.Settings.themeOnSurfaceVariant.value, SysRole.ON_SURFACE_VARIANT, dl(0x80_FFFFFF.toInt(), 0x99_000000.toInt()))
    /** Miuix onSurfaceVariantActions: #66000000 (light) / #66FFFFFF (dark) — arrows/secondary actions. */
    val onSurfaceTip: Int get() = tok(momoi.mod.qqpro.Settings.themeOnSurfaceTip.value, SysRole.ON_SURFACE_TIP, dl(0x66_FFFFFF.toInt(), 0x66_000000.toInt()))
    val hint: Int get() = tok(momoi.mod.qqpro.Settings.themeHint.value, SysRole.HINT, dl(0x66_FFFFFF.toInt(), 0x66_000000.toInt()))
    /** Miuix onBackgroundVariant: #8C93B0 (light) / #787E96 (dark) — SmallTitle group labels. */
    val onBackgroundVariant: Int get() = dl(0xFF_787E96.toInt(), 0xFF_8C93B0.toInt())
    /** Miuix onSurfaceSecondary: #CC000000 (light) / #CCFFFFFF (dark) — dialog summaries. */
    val onSurfaceSecondary: Int get() = dl(0xCC_FFFFFF.toInt(), 0xCC_000000.toInt())
    /** Miuix outline: #D9D9D9 (light) / #404040 (dark) — borders. */
    val outline: Int get() = tok(momoi.mod.qqpro.Settings.themeOutline.value, SysRole.OUTLINE, dl(0xFF_404040.toInt(), 0xFF_D9D9D9.toInt()))
    val outlineVariant: Int get() = tok(momoi.mod.qqpro.Settings.themeOutlineVariant.value, SysRole.OUTLINE_VARIANT, dl(0xFF_393939.toInt(), 0xFF_E0E0E0.toInt()))
    /** Miuix dividerLine: #E0E0E0 (light) / #393939 (dark) — hairline dividers. */
    val dividerLine: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themeDivider.value)
        ?: dl(0xFF_393939.toInt(), 0xFF_E0E0E0.toInt())

    // ── Status (Miuix error role) ────────────────────────────────────────────────
    val error: Int get() = parseColor(momoi.mod.qqpro.Settings.themeError.value, dl(0xFF_F12522.toInt(), 0xFF_E94634.toInt()))
    val badge: Int get() = error                   // unread/notification badge follows the error token (Miuix BadgeDefaults)
    val legacyBtn get() = primary                  // Colors.btn now = the Miuix accent

    // ── Dimming / slider ─────────────────────────────────────────────────────────
    /** Miuix windowDimming: black 30% (light) / 60% (dark) — dialog/popup scrim. */
    val windowDimming: Int get() = dl(0x99_000000.toInt(), 0x4D_000000.toInt())
    /** Miuix sliderBackground: #0F000000 (light) / #26FFFFFF (dark) — slider track. */
    val sliderBackground: Int get() = dl(0x26_FFFFFF.toInt(), 0x0F_000000.toInt())

    // ── Disabled (Miuix disabled roles) ─────────────────────────────────────────
    val disabledPrimary: Int get() = dl(0xFF_253E64.toInt(), 0xFF_C2D9FF.toInt())
    val disabledOnPrimary: Int get() = dl(0xFF_677993.toInt(), 0xFF_F3F8FF.toInt())
    val disabledSecondary: Int get() = dl(0xFF_3F3F3F.toInt(), 0xFF_F0F0F0.toInt())
    val disabledOnSurface: Int get() = dl(0xFF_666666.toInt(), 0xFF_B2B2B2.toInt())

    // ── Chat tokens (referenced by Colors aliases; values must stay exact) ───────
    val replyText = 0xFF_DDDDDD.toInt()
    val replyBackground = 0x22_FFFFFF
    val atMe = 0xFF_F74C30.toInt()

    // Group role/level tag badges (头衔/普通/管理/群主). Mode-aware: the dark values are unchanged; light
    // mode uses a pale tint background with a deeper, readable text color so the tags don't show as dark
    // chips on a light bubble.
    object NickTag {
        val specialBg get() = dl(0xFF_30263F.toInt(), 0xFF_ECE3FB.toInt()); val specialText get() = dl(0xFF_BB87F7.toInt(), 0xFF_6A3FD0.toInt())
        val normalBg  get() = dl(0xFF_2E2E2E.toInt(), 0xFF_E4E4E4.toInt()); val normalText  get() = dl(0xFF_9E9E9E.toInt(), 0xFF_5F5F5F.toInt())
        val adminBg   get() = dl(0xFF_0E2D41.toInt(), 0xFF_D6E9FB.toInt()); val adminText  get() = dl(0xFF_0088EE.toInt(), 0xFF_0066CC.toInt())
        val ownerBg   get() = dl(0xFF_412917.toInt(), 0xFF_FCE6D2.toInt()); val ownerText  get() = dl(0xFF_FF8D40.toInt(), 0xFF_C25700.toInt())
    }

    // ── Shape (Miuix component Defaults) ─────────────────────────────────────────
    // CardDefaults.CornerRadius = 16dp; ButtonDefaults.CornerRadius = 16dp; DialogDefaults.
    // CornerRadius = 32dp. Small shapes (8/14dp) follow the same family for chips/fields.
    val radiusSm get() = 8.dp.toFloat()
    val radiusMd get() = 14.dp.toFloat()
    val radiusLg get() = 16.dp.toFloat()      // Miuix Card / Button radius
    val radiusXl get() = 24.dp.toFloat()
    val radiusDialog get() = 32.dp.toFloat()  // Miuix DialogDefaults.CornerRadius
    val radiusPill = 9999f

    // ── Component dimens (Miuix Defaults, verbatim) ─────────────────────────────
    val cardRadius = 16.dp                    // CardDefaults.CornerRadius
    val buttonMinWidth = 58.dp                // ButtonDefaults.MinWidth
    val buttonMinHeight = 40.dp               // ButtonDefaults.MinHeight
    val buttonInsetH = 16.dp                  // ButtonDefaults.InsideMargin horizontal
    val buttonInsetV = 13.dp                  // ButtonDefaults.InsideMargin vertical
    val switchW = 49.dp                       // Switch track width
    val switchH = 28.dp                       // Switch track height
    val switchThumb = 20.dp                   // Switch thumb size
    val checkboxSize = 26.dp                  // CheckboxDefaults size
    val rowMinHeight = 56.dp                  // BasicComponentDefaults min height
    val rowInset = 16.dp                      // BasicComponentDefaults.InsideMargin
    val smallTitlePadH = 28.dp                // SmallTitleDefaults.InsideMargin horizontal
    val smallTitlePadV = 8.dp                 // SmallTitleDefaults.InsideMargin vertical
    val searchMinHeight = 45.dp               // SearchBarDefaults.InputFieldMinHeight
    val dialogInset = 24.dp                   // DialogDefaults.insideMargin
    val dialogOuterMargin = 12.dp             // DialogDefaults.outsideMargin

    // ── Type scale (Miuix TextStyles, verbatim sp sizes) ────────────────────────
    val typeTitle1 = 32f     // page hero titles
    val typeTitle2 = 24f
    val typeTitle3 = 20f
    val typeTitle4 = 18f
    val typeHeadline = 17f   // row titles (BasicComponent: 17sp, Medium weight)
    val typeMain = 17f       // main / paragraph / button / headline1
    val typeBody1 = 16f
    val typeBody2 = 14f      // summaries / subtitle (subtitle = 14sp Bold)
    val typeFootnote1 = 13f
    val typeFootnote2 = 11f

    // ── Deprecated aliases (kept so existing call sites keep compiling) ──────────
    val ACCENT get() = primary
    val TONAL get() = primaryContainer
    val ON_SURFACE_VARIANT get() = onSurfaceVariant
    val BADGE get() = badge
    val SURFACE get() = surfaceContainer
    val HINT get() = hint
    val ON_SURFACE get() = onSurface

    /**
     * Parse a hex color string (`#RRGGBB`, `RRGGBB`, `#AARRGGBB` or `AARRGGBB`, with optional spaces);
     * a parse without an alpha component is treated as fully opaque. Returns [fallback] when blank/invalid.
     */
    fun parseColor(raw: String?, fallback: Int): Int = parseColorOrNull(raw) ?: fallback

    /** Like [parseColor] but returns null (not a fallback) when the string is blank/invalid. */
    fun parseColorOrNull(raw: String?): Int? {
        val s = raw?.trim()?.removePrefix("#")?.replace(" ", "") ?: return null
        if (s.length != 6 && s.length != 8) return null
        val v = s.toLongOrNull(16) ?: return null
        return if (s.length == 6) (0xFF000000.toInt() or v.toInt()) else v.toInt()
    }

    /**
     * Best-contrast label color (near-black or white) for content drawn ON a filled [bg] of an
     * arbitrary color. Use for any button/badge whose background isn't a fixed token.
     */
    fun onColor(bg: Int): Int = if (luminance(bg) > 0.6f) 0xFF_111111.toInt() else 0xFF_FFFFFF.toInt()

    /** Perceived luminance of [color] in 0..1 (ignores alpha). */
    fun luminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Composite a (possibly translucent) [fg] over an opaque [bg], returning an opaque color. */
    fun compositeOver(fg: Int, bg: Int): Int {
        val a = (fg ushr 24 and 0xFF) / 255f
        fun mix(sh: Int) = (((fg shr sh and 0xFF) * a) + ((bg shr sh and 0xFF) * (1f - a))).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Per-channel linear blend from [a] toward [b] by [t] in 0..1; result is opaque. */
    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(sh: Int) = ((a shr sh and 0xFF) * (1 - k) + (b shr sh and 0xFF) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun darken(color: Int, amount: Float): Int {
        val k = (1f - amount).coerceIn(0f, 1f)
        val a = color ushr 24 and 0xFF
        val r = ((color shr 16 and 0xFF) * k).toInt()
        val g = ((color shr 8 and 0xFF) * k).toInt()
        val b = ((color and 0xFF) * k).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** A solid rounded rect drawable. */
    fun rounded(color: Int, radius: Float = radiusMd): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    /** A stroked (outlined) rounded rect drawable. */
    fun outlined(strokeColor: Int, radius: Float = radiusMd, strokeWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(0)
            cornerRadius = radius
            setStroke(strokeWidthDp.dp, strokeColor)
        }

    /** A rounded rect that is BOTH filled ([fillColor]) and outlined ([strokeColor]). */
    fun filledOutlined(
        fillColor: Int, strokeColor: Int, radius: Float = radiusMd, strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = radius
        setStroke(strokeWidthDp.dp, strokeColor)
    }

    /**
     * Wrap a content drawable with a MIUIX ripple (pressed feedback). The mask BOUNDS the ripple.
     */
    fun ripple(content: Drawable?, rippleColor: Int = dl(0x1F_FFFFFF, 0x12_000000)): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, content ?: ColorDrawable(Color.WHITE))

    /**
     * Attach a MIUI Folme-style physical spring touch-recoil feedback to [view].
     * Scales slightly down on press (e.g. 0.96f) and recoils with a gentle spring overshoot on lift.
     */
    fun attachMiuixTouchFeedback(view: View, targetScale: Float = 0.96f) {
        var pressSpring: SpringAnimator? = null
        val releaseDelayMs = 80L
        val releaseRunnable = Runnable {
            pressSpring = SpringAnimator()
                .stiffness(M3Motion.SpringStiffnessLow)
                .dampingRatio(M3Motion.SpringDampingMediumBouncy)
                .startFrom(view.scaleX)
                .apply { animateTo(1f) { s -> view.scaleX = s; view.scaleY = s } }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    view.scaleX = targetScale
                    view.scaleY = targetScale
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    v.postDelayed(releaseRunnable, releaseDelayMs)
                }
            }
            false
        }
    }

    /** A MIUIX search/text field pill (Miuix InputField: 45dp min height, round fill). */
    fun searchField(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(onInputFill)
        setTextColor(onSurface)
        textSize = typeFootnote1
        setSingleLine()
        setPadding(16.dp, 0, 16.dp, 0)
        minHeight = searchMinHeight
        background = rounded(surfaceContainerHigh, radiusPill)
    }
}

/**
 * A circular tonal icon button with an optional top-end unread badge. Self-contained
 * (no XML, no Material lib dependency — the watch theme isn't MaterialComponents-based, so a real
 * com.google.android.material button would crash). Reuse for any toolbar/action-bar action.
 */
class MaterialIconButton(ctx: Context) : FrameLayout(ctx) {
    private val icon = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val badge = TextView(ctx).apply {
        setTextColor(0xFF_FFFFFF.toInt())
        textSize = 8f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = 9999f; setColor(M3.BADGE)
        }
        setPadding(3.dp, 0, 3.dp, 0)
        minWidth = 12.dp
        visibility = View.GONE
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        // No container/tint by default: many app icons are already full-color round badges, so a
        // tonal circle behind them and an accent tint just collapse them into blobs. Opt in via
        // [setTonalContainer] / [setIconTint] for monochrome icons that need it.
        addView(icon, LayoutParams(22.dp, 22.dp, Gravity.CENTER))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, 13.dp, Gravity.TOP or Gravity.END))
    }

    /** Set the icon from a resource name (app resources aren't on the compile-time R). */
    fun setIconResName(name: String) {
        val context = context
        val resources = context.resources
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        if (id != 0) runCatching { icon.setImageResource(id) }
    }

    /** Set the icon from a drawable (e.g. a self-drawn icon). */
    fun setIcon(drawable: android.graphics.drawable.Drawable) = icon.setImageDrawable(drawable)

    fun setIconTint(color: Int) = icon.setColorFilter(color)

    /** Enable the accent-tile circular container behind the icon (for monochrome/line icons). */
    fun setTonalContainer() {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(M3.TONAL) }
    }

    /** Show/hide the unread badge ("99+" cap); count <= 0 hides it. */
    fun setBadgeCount(count: Int) {
        if (count > 0) {
            badge.text = if (count > 99) "99+" else count.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }
}
