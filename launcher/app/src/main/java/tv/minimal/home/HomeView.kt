package tv.minimal.home

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar

/**
 * The home screen: a full-bleed rotating wallpaper with a small clock up top and a frosted dock of
 * streaming apps along the bottom; Down from the dock glides up to the All Apps grid.
 *
 * It's a plain view so it can live in two places: the launcher activity, and the instant overlay
 * [HomeRedirectService] shows while Android holds back the activity after a Home press.
 */
@SuppressLint("ViewConstructor")
class HomeView(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(app: AppEntry, from: View)
        fun open(intent: Intent)
    }

    private val pad = context.dpi(56)
    private val dockPad = context.dpi(18)
    private val screenH = resources.displayMetrics.heightPixels

    private val dim: View
    private val page: GlideFrame
    private lateinit var greeting: TextView
    private lateinit var inputs: Pill
    private lateinit var settings: Pill
    private lateinit var customize: Pill
    private lateinit var originalHome: Pill
    private lateinit var selectionLabel: TextView
    private lateinit var selectionHint: TextView
    private val caption: TextView
    private lateinit var dock: FrameLayout
    private lateinit var dockFrame: GlideFrame
    private lateinit var dockRow: LinearLayout
    private lateinit var hint: TextView
    private val allTitle: TextView
    private val grid: LinearLayout
    private var firstTile: View? = null
    private var builtVersion = 0
    private var heroVisibility = 1f // 1 on the first screen, 0 once the grid has slid up

    init {
        setBackgroundColor(0xFF07090D.toInt())
        val wallpaper = WallpaperLayer(context)
        addView(wallpaper, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // shade the top (clock) and bottom (dock) so text reads over any photo
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB30A0D14.toInt(), 0x300A0D14, 0x120A0D14, 0x660A0D14, 0xE60A0D14.toInt()),
            )
        }, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        dim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        addView(dim, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, context.dpi(64))
        }
        page = GlideFrame(context, horizontal = false)
        page.addView(column, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(page, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // First screen: header at the top, dock at the bottom, wallpaper in between.
        val hero = FrameLayout(context).apply { clipChildren = false }
        column.addView(hero, LinearLayout.LayoutParams(MATCH_PARENT, screenH))
        hero.addView(buildHeader(), LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = context.dpi(28)
        })
        hero.addView(buildDockArea(), LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = context.dpi(20)
        })
        // Photo location + credit, on the same line as the "All Apps" hint.
        caption = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            alpha = 0f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = (resources.displayMetrics.widthPixels * 0.36f).toInt()
            softShadow()
        }
        hero.addView(caption, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = context.dpi(20)
            marginEnd = pad
        })
        wallpaper.onFrame = { frame, fade -> showCaption(frame.caption, fade > 0) }

        // Below the fold: everything else.
        allTitle = TextView(context).apply {
            setText(R.string.all_apps)
            textSize = 26f
            typeface = MEDIUM
            letterSpacing = 0.02f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(pad, 0, pad, 0)
        }
        column.addView(allTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = context.dpi(8)
        })
        grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(pad, context.dpi(14), pad, 0)
        }
        column.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Dim the wallpaper and hide the hint as the grid slides up.
        page.setOnScrollChangeListener { _, _, y, _, _ ->
            val t = (y / (screenH * 0.55f)).coerceIn(0f, 1f)
            dim.alpha = 0.65f * t
            heroVisibility = (1f - t * 3f).coerceAtLeast(0f)
            hint.alpha = HINT_ALPHA * heroVisibility
            if (caption.text.isNotEmpty()) caption.alpha = CAPTION_ALPHA * heroVisibility
        }

        loadApps(focusPkg = null)
    }

    // ---- public API -------------------------------------------------------------------------

    /** Package of the focused app tile, if an app tile has focus. */
    val focusedPkg: String? get() = (findFocus() as? AppTile)?.app?.pkg

    /** Back to the dock's first app, like pressing Home on tvOS. */
    fun goToTop() {
        page.glideTo(0)
        dockFrame.glideTo(0)
        firstTile?.requestFocus()
    }

    /** Focus [pkg]'s tile (or the first tile) and jump there without any animation. */
    fun showAt(pkg: String?) {
        val target = pkg?.let { p -> allTiles().firstOrNull { it.app.pkg == p } } ?: firstTile
        target?.requestFocus()
        page.finishGlide()
        dockFrame.finishGlide()
        settleAll()
    }

    /** Mirror another home view's focus and scroll positions exactly, without animating. */
    fun mirror(other: HomeView) {
        val otherFocus = other.findFocus()
        val target: View? = when {
            otherFocus === other.inputs -> inputs
            otherFocus === other.settings -> settings
            otherFocus === other.customize -> customize
            otherFocus === other.originalHome -> originalHome
            else -> other.focusedPkg?.let { p -> allTiles().firstOrNull { it.app.pkg == p } } ?: firstTile
        }
        target?.requestFocus()
        page.glideTo(other.page.scrollY, animated = false)
        dockFrame.glideTo(other.dockFrame.scrollX, animated = false)
        settleAll()
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, now -> onFocusMoved(now) }
    private val appsListener: () -> Unit = { loadApps((findFocus() as? AppTile)?.app?.pkg) }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateGreeting()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        Apps.addListener(appsListener)
        if (builtVersion != Apps.version) appsListener()
        updateGreeting()
        context.registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        Apps.removeListener(appsListener)
        context.unregisterReceiver(timeReceiver)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Home has nowhere to go back to; Back just returns to the dock.
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goToTop()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- building -------------------------------------------------------------------------------

    private fun TextView.softShadow() = setShadowLayer(context.dp(10), 0f, context.dp(1), 0x73000000)

    private fun buildHeader(): View {
        greeting = TextView(context).apply {
            textSize = 12f
            typeface = MEDIUM
            setTextColor(0xBFFFFFFF.toInt())
            softShadow()
        }
        val wordmark = TextView(context).apply {
            setText(R.string.app_name)
            textSize = 30f
            typeface = MEDIUM
            letterSpacing = -0.03f
            setTextColor(Color.WHITE)
            softShadow()
        }
        val clock = TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 25f
            typeface = LIGHT
            setTextColor(Color.WHITE)
            gravity = Gravity.END
            softShadow()
        }
        val date = TextClock(context).apply {
            format12Hour = "EEE, d. MMM"
            format24Hour = "EEE, d. MMM"
            textSize = 11f
            setTextColor(0xBFFFFFFF.toInt())
            gravity = Gravity.END
            softShadow()
        }
        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(wordmark)
            addView(greeting)
        }
        val time = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(clock)
            addView(date)
        }
        val masthead = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(brand, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(time)
        }
        originalHome = Pill(context, R.drawable.ic_home, context.getString(R.string.fire_tv)).apply {
            setOnClickListener { HomeRedirectService.openOriginalHome(context) }
        }
        inputs = Pill(context, R.drawable.ic_input, context.getString(R.string.inputs)).apply {
            setOnClickListener { openInputs() }
        }
        settings = Pill(context, R.drawable.ic_settings, context.getString(R.string.settings)).apply {
            setOnClickListener { host.open(HomeRedirectService.amazonSettingsIntent()) }
        }
        customize = Pill(context, R.drawable.ic_customize, context.getString(R.string.customize)).apply {
            setOnClickListener { host.open(Intent(context, CustomizeActivity::class.java)) }
        }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            listOf(originalHome, inputs, customize, settings).forEachIndexed { index, control ->
                addView(control, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (index > 0) marginStart = context.dpi(12)
                })
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(pad, 0, pad, 0)
            addView(masthead)
            addView(controls, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = context.dpi(18)
            })
        }
    }

    private fun buildDockArea(): View {
        dockRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dockPad, dockPad, dockPad, dockPad)
        }
        dockFrame = GlideFrame(context, horizontal = true, edgeDp = 20f)
        dockFrame.addView(dockRow, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        dock = FrameLayout(context).apply {
            // Frosted-glass panel: translucent fill, hairline border, clipped to its rounded shape.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x80424B5C.toInt(), 0xB31C2331.toInt()),
            ).apply {
                cornerRadius = context.dp(28)
                setStroke(context.dpi(1), 0x40FFFFFF)
            }
            elevation = context.dp(8)
            clipToOutline = true
            addView(dockFrame, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        hint = TextView(context).apply {
            setText(R.string.all_apps)
            textSize = 12f
            typeface = MEDIUM
            letterSpacing = 0.06f
            setTextColor(Color.WHITE)
            alpha = HINT_ALPHA
            gravity = Gravity.CENTER
            softShadow()
            val chevron = context.getDrawable(R.drawable.ic_chevron_down)?.mutate()?.apply {
                setBounds(0, 0, context.dpi(16), context.dpi(16))
                setTint(Color.WHITE)
            }
            setCompoundDrawablesRelative(null, null, chevron, null)
            compoundDrawablePadding = context.dpi(2)
        }
        selectionLabel = TextView(context).apply {
            setText(R.string.quick_access)
            textSize = 38f
            typeface = MEDIUM
            letterSpacing = -0.03f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            softShadow()
        }
        selectionHint = TextView(context).apply {
            setText(R.string.customize_hint)
            textSize = 13f
            setTextColor(0xC7FFFFFF.toInt())
            softShadow()
        }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(selectionLabel)
            addView(selectionHint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = context.dpi(3)
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(heading, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = pad
                marginEnd = pad
                bottomMargin = context.dpi(18)
            })
            addView(dock, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = pad
                marginEnd = pad
            })
            addView(hint, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = context.dpi(10)
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
    }

    private fun loadApps(focusPkg: String?) {
        val catalog = Apps.catalog(context)
        builtVersion = Apps.version
        val contentW = resources.displayMetrics.widthPixels - 2 * pad

        dockRow.removeAllViews()
        val dockGap = context.dpi(16)
        val dockW = context.dpi(148)
        catalog.favorites.forEachIndexed { i, app ->
            dockRow.addView(tile(app, cornerDp = 16f), LinearLayout.LayoutParams(dockW, dockW * 9 / 16).apply {
                if (i > 0) marginStart = dockGap
            })
        }
        dock.visibility = if (catalog.favorites.isEmpty()) View.GONE else View.VISIBLE

        grid.removeAllViews()
        val cols = 5
        val gap = context.dpi(28)
        val w = (contentW - (cols - 1) * gap) / cols
        catalog.others.chunked(cols).forEachIndexed { r, rowApps ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            rowApps.forEachIndexed { i, app ->
                row.addView(tile(app, cornerDp = 14f), LinearLayout.LayoutParams(w, w * 9 / 16).apply {
                    if (i > 0) marginStart = gap
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap
            })
        }
        allTitle.visibility = if (catalog.others.isEmpty()) View.GONE else View.VISIBLE
        hint.visibility = allTitle.visibility

        val tiles = allTiles()
        firstTile = if (catalog.favorites.isNotEmpty()) dockRow.getChildAt(0) else customize
        val target = focusPkg?.let { pkg -> tiles.firstOrNull { it.app.pkg == pkg } } ?: firstTile
        post { target?.requestFocus() }
    }

    private fun tile(app: AppEntry, cornerDp: Float) = AppTile(context, app, cornerDp).apply {
        setOnClickListener { host.launch(app, this) }
    }

    private fun allTiles(): List<AppTile> {
        val out = ArrayList<AppTile>()
        fun walk(v: View) {
            if (v is AppTile) out.add(v)
            else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(dockRow)
        walk(grid)
        return out
    }

    private fun settleAll() {
        allTiles().forEach { it.settle() }
        inputs.settle()
        settings.settle()
        customize.settle()
        originalHome.settle()
    }

    private fun onFocusMoved(now: View?) {
        now ?: return
        selectionLabel.text = when (now) {
            is AppTile -> now.app.label
            originalHome -> context.getString(R.string.fire_tv)
            inputs -> context.getString(R.string.inputs)
            settings -> context.getString(R.string.settings)
            else -> context.getString(R.string.quick_access)
        }
        selectionHint.setText(when (now) {
            is AppTile -> R.string.open_app_hint
            originalHome -> R.string.fire_tv_hint
            inputs, settings -> R.string.open_settings_hint
            else -> R.string.customize_hint
        })
        var p: android.view.ViewParent? = now.parent
        while (p != null) {
            if (p is GlideFrame) p.reveal(now)
            p = p.parent
        }
    }

    /** Amazon's Inputs screen is signature-protected; open Settings and let the service click "Inputs". */
    private fun openInputs() {
        HomeRedirectService.pendingInputsClickUntil = SystemClock.uptimeMillis() + 4000
        host.open(HomeRedirectService.amazonSettingsIntent())
    }

    /** "Grand Canal, Venice (© Credit)" → place in white, credit dimmer. */
    private fun showCaption(text: String?, animate: Boolean) {
        val styled = text?.let {
            val split = it.indexOf(" (©")
            if (split < 0) SpannableString(it) else SpannableString(
                it.substring(0, split) + "  ·  " + it.substring(split + 2).removeSuffix(")"),
            ).apply {
                setSpan(ForegroundColorSpan(0x99FFFFFF.toInt()), split, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        caption.animate().cancel()
        if (!animate) {
            caption.text = styled ?: ""
            caption.alpha = if (styled != null) CAPTION_ALPHA * heroVisibility else 0f
            return
        }
        caption.animate().alpha(0f).setDuration(400).withEndAction {
            caption.text = styled ?: ""
            if (styled != null) caption.animate().alpha(CAPTION_ALPHA * heroVisibility).setDuration(900).start()
        }.start()
    }

    private fun updateGreeting() {
        greeting.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> context.getString(R.string.good_morning)
            in 12..16 -> context.getString(R.string.good_afternoon)
            else -> context.getString(R.string.good_evening)
        }
    }

    companion object {
        private const val HINT_ALPHA = 0.55f
        private const val CAPTION_ALPHA = 0.8f
    }
}
