package com.example.ioslauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.List
import java.util.Locale
import java.util.Map
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val COLS = 4

    data class AppItem(
        var label: String = "",
        var pkg: String = "",
        var cn: ComponentName? = null,
        var intent: Intent? = null
    )

    private val allApps = mutableListOf<AppItem>()
    private val dockApps = mutableListOf<AppItem>()
    private val searchHits = mutableListOf<AppItem>()
    private val iconCache = mutableMapOf<String, Bitmap>()

    private lateinit var pm: PackageManager
    private var density = 0f
    private var screenW = 0
    private var iconSize = 0
    private var rows = 6
    private var currentPage = 0

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var pagesRow: LinearLayout
    private lateinit var dotsRow: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var pager: SnapScroll
    private lateinit var searchOverlay: FrameLayout
    private lateinit var searchResults: LinearLayout
    private lateinit var searchField: EditText

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            iconCache.clear()
            loadApps()
            rebuildHome()
            if (searchOverlay.visibility == View.VISIBLE) {
                populateSearch(searchField.text.toString())
            }
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            pm = packageManager
            density = resources.displayMetrics.density
            screenW = resources.displayMetrics.widthPixels
            iconSize = dp(60)

            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(false)
            }

            buildShell()
            setContentView(root)

            loadApps()
            rebuildHome()

            val f = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pkgReceiver, f)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Launcher initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pkgReceiver)
        } catch (e: Exception) {
            // ignored
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while launcher is already visible
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) {
            closeSearch()
        } else if (::pager.isInitialized && currentPage != 0) {
            pager.snapTo(0)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) {
            closeSearch()
        }
        // A launcher never finishes on Back.
    }

    // ------------------------------------------------------------ UI shell

    private fun buildShell() {
        root = FrameLayout(this)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        pagesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        pager = SnapScroll(this).apply {
            addView(pagesRow, FrameLayout.LayoutParams(WRAP, MATCH))
            addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                val h = (b - t) - dp(12)
                val nr = maxOf(3, minOf(8, h / dp(96)))
                if (nr != rows) {
                    rows = nr
                    post { rebuildHome() }
                }
            }
        }
        content.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(dotsRow, LinearLayout.LayoutParams(MATCH, dp(24)))

        val pill = TextView(this).apply {
            text = "Search"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = roundBg(0x40FFFFFF.toInt(), 17)
            setOnClickListener { openSearch() }
        }
        val pillLp = LinearLayout.LayoutParams(dp(130), dp(34)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        }
        content.addView(pill, pillLp)

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = roundBg(0x40FFFFFF.toInt(), 30)
        }
        val dockLp = LinearLayout.LayoutParams(MATCH, dp(86)).apply {
            setMargins(dp(12), 0, dp(12), dp(10))
        }
        content.addView(dock, dockLp)

        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        buildSearchOverlay()

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            content.setPadding(0, bars.top, 0, bars.bottom)
            searchOverlay.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
    }

    private fun buildSearchOverlay() {
        searchOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xE6101014.toInt())
            visibility = View.GONE
            isClickable = true
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }

        searchField = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            background = roundBg(0x33FFFFFF.toInt(), 14)
            setPadding(dp(16), 0, dp(16), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
                    populateSearch(s.toString())
                }
                override fun afterTextChanged(s: Editable) {}
            })
            setOnEditorActionListener { tv, id, ev ->
                if (searchHits.isNotEmpty()) {
                    launch(searchHits[0], tv)
                }
                true
            }
        }
        box.addView(searchField, LinearLayout.LayoutParams(MATCH, dp(48)))

        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        searchResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sv.addView(searchResults, FrameLayout.LayoutParams(MATCH, WRAP))
        val svLp = LinearLayout.LayoutParams(MATCH, 0, 1f).apply {
            topMargin = dp(12)
        }
        box.addView(sv, svLp)

        searchOverlay.addView(box, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(searchOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------ data

    private fun loadApps() {
        allApps.clear()
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
        for (ri in list) {
            ri.activityInfo?.let {
                val pkg = it.packageName
                if (pkg == packageName) return@let
                val item = AppItem()
                item.pkg = pkg
                item.cn = ComponentName(pkg, it.name)
                item.label = ri.loadLabel(pm).toString()
                item.intent = Intent(main).setComponent(item.cn!!)
                allApps.add(item)
            }
        }
        Collections.sort(allApps) { a, b -> a.label.compareTo(b.label, ignoreCase = true) }
        loadDock()
    }

    private fun loadDock() {
        dockApps.clear()
        val slots = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        for (base in slots) {
            val item = resolveSlot(base)
            if (item == null) continue
            val dup = dockApps.any { it.pkg == item.pkg }
            if (!dup) dockApps.add(item)
        }
    }

    private fun resolveSlot(base: Intent): AppItem? {
        var ri = pm.resolveActivity(base, 0)
        if (ri == null || ri.activityInfo == null || "android" == ri.activityInfo.packageName) {
            val l = pm.queryIntentActivities(base, 0)
            ri = if (l.isEmpty()) null else l[0]
        }
        if (ri == null || ri.activityInfo == null) return null
        val item = AppItem()
        item.pkg = ri.activityInfo.packageName
        item.cn = ComponentName(item.pkg, ri.activityInfo.name)
        item.label = ri.loadLabel(pm).toString()
        item.intent = Intent(base).setComponent(item.cn!!)
        return item
    }

    private fun gridApps(): java.util.List<AppItem> {
        return allApps.filter { a ->
            !dockApps.any { it.pkg == a.pkg }
        } as java.util.List<AppItem>
    }

    // ------------------------------------------------------------ home grid

    private fun rebuildHome() {
        pagesRow.removeAllViews()
        val grid = gridApps()
        val per = rows * COLS
        val pages = maxOf(1, (grid.size + per - 1) / per)
        val cellW = (screenW - dp(16)) / COLS

        for (p in 0 until pages) {
            val page = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(12), dp(8), 0)
            }
            for (r in 0 until rows) {
                if (p * per + r * COLS >= grid.size) break
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                for (c in 0 until COLS) {
                    val idx = p * per + r * COLS + c
                    val lp = LinearLayout.LayoutParams(cellW, dp(96))
                    if (idx < grid.size) {
                        row.addView(makeCell(grid[idx], true), lp)
                    } else {
                        row.addView(View(this), lp)
                    }
                }
                page.addView(row, LinearLayout.LayoutParams(MATCH, dp(96)))
            }
            pagesRow.addView(page, LinearLayout.LayoutParams(screenW, MATCH))
        }

        if (currentPage >= pages) currentPage = pages - 1
        buildDots(pages)
        buildDock()
        val target = currentPage
        pager.post { pager.scrollTo(target * screenW, 0) }
    }

    private fun buildDots(pages: Int) {
        dotsRow.removeAllViews()
        if (pages < 2) return
        for (i in 0 until pages) {
            val d = View(this)
            val g = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                color = Color.WHITE
            }
            d.background = g
            val lp = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            dotsRow.addView(d, lp)
        }
        updateDots(currentPage)
    }

    private fun updateDots(page: Int) {
        for (i in 0 until dotsRow.childCount) {
            dotsRow.getChildAt(i).alpha = if (i == page) 1f else 0.4f
        }
    }

    private fun buildDock() {
        dock.removeAllViews()
        for (it in dockApps) {
            dock.addView(makeCell(it, false), LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        dock.visibility = if (dockApps.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun makeCell(item: AppItem, withLabel: Boolean): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (withLabel) (Gravity.TOP or Gravity.CENTER_HORIZONTAL) else Gravity.CENTER
            setPadding(0, if (withLabel) dp(6) else 0, 0, 0)
        }

        val iv = ImageView(this).apply {
            setImageBitmap(getIcon(item))
        }
        cell.addView(iv, LinearLayout.LayoutParams(iconSize, iconSize))

        if (withLabel) {
            val tv = TextView(this).apply {
                text = item.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
                setPadding(dp(2), 0, dp(2), 0)
            }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(5)
            }
            cell.addView(tv, lp)
        }

        cell.setOnClickListener { launch(item, it) }
        cell.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu(item, it)
            true
        }
        cell.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false
        }
        return cell
    }

    // ------------------------------------------------------------ actions

    private fun launch(item: AppItem, from: View) {
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) closeSearch()
        val i = Intent(item.intent!!).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(
                from, 0, 0, maxOf(1, from.width), maxOf(1, from.height)
            ).toBundle()
            startActivity(i, opts)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${item.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(item: AppItem, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "App info")
        menu.menu.add(0, 2, 1, "Uninstall")
        menu.setOnMenuItemClickListener { mi ->
            try {
                when (mi.itemId) {
                    1 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", item.pkg, null))
                    )
                    2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.pkg}")))
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show()
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------ search

    private fun openSearch() {
        searchField.setText("")
        populateSearch("")
        searchOverlay.alpha = 0f
        searchOverlay.visibility = View.VISIBLE
        searchOverlay.animate().alpha(1f).setDuration(150).start()
        searchField.requestFocus()
        searchField.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun closeSearch() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchOverlay.visibility = View.GONE
        searchField.setText("")
    }

    private fun populateSearch(q: String) {
        searchResults.removeAllViews()
        searchHits.clear()
        val needle = q.trim().lowercase(Locale.getDefault())
        for (a in allApps) {
            if (needle.isEmpty() || a.label.lowercase(Locale.getDefault()).contains(needle)) {
                searchHits.add(a)
            }
        }
        val cellW = (screenW - dp(32)) / COLS
        var row: LinearLayout? = null
        for (i in searchHits.indices) {
            if (i % COLS == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                searchResults.addView(row, LinearLayout.LayoutParams(MATCH, dp(96)))
            }
            row?.addView(makeCell(searchHits[i], true), LinearLayout.LayoutParams(cellW, dp(96)))
        }
    }

    // ------------------------------------------------------------ icons

    private fun getIcon(item: AppItem): Bitmap {
        val key = item.cn!!.flattenToString()
        val cached = iconCache[key]
        if (cached != null) return cached
        val d: Drawable = try {
            pm.getActivityIcon(item.cn!!)
        } catch (e: Exception) {
            pm.getDefaultActivityIcon()
        }
        val b = renderIcon(d, iconSize)
        iconCache[key] = b
        return b
    }

    /** Draws the app icon filling a rounded-square (iOS style) instead of the system mask. */
    private fun renderIcon(d: Drawable, s: Int): Bitmap {
        val tmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(tmp)
        if (d is AdaptiveIconDrawable) {
            val ex = (s * 0.25f).roundToInt() // layers are 108dp, visible area is the central 72dp
            val bg = d.background
            val fg = d.foreground
            if (bg != null) {
                bg.setBounds(-ex, -ex, s + ex, s + ex)
                bg.draw(c)
            } else {
                c.drawColor(Color.WHITE)
            }
            if (fg != null) {
                fg.setBounds(-ex, -ex, s + ex, s + ex)
                fg.draw(c)
            }
        } else {
            c.drawColor(Color.WHITE)
            val pad = (s * 0.12f).roundToInt()
            d.setBounds(pad, pad, s - pad, s - pad)
            d.draw(c)
        }

        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = BitmapShader(tmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val r = s * 0.225f
        Canvas(out).drawRoundRect(RectF(0f, 0f, s.toFloat(), s.toFloat()), r, r, p)
        return out
    }

    // ------------------------------------------------------------ helpers

    private fun dp(v: Float): Int = (v * density).roundToInt()

    private fun roundBg(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            this.color = color
            cornerRadius = dp(radiusDp)
        }
    }

    /** Horizontal pager that always snaps to a full page, like the iOS home screen. */
    private class SnapScroll(private val activity: MainActivity) : HorizontalScrollView(activity) {
        private var vt: VelocityTracker? = null
        private var startPage = 0

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        private fun pageNow(): Int = (scrollX / activity.screenW.toFloat()).roundToInt()

        fun snapTo(page: Int) {
            val max = maxOf(0, activity.pagesRow.childCount - 1)
            val t = maxOf(0, minOf(max, page))
            activity.currentPage = t
            activity.updateDots(t)
            smoothScrollTo(t * activity.screenW, 0)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                startPage = pageNow()
                vt?.recycle()
                vt = VelocityTracker.obtain()
            }
            vt?.addMovement(ev)
            return super.dispatchTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val a = ev.actionMasked
            val handled = super.onTouchEvent(ev)
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                var vx = 0f
                vt?.apply {
                    computeCurrentVelocity(1000)
                    vx = xVelocity
                }
                var target = pageNow()
                if (abs(vx) > 700f) {
                    target = startPage + if (vx < 0) 1 else -1
                }
                snapTo(target)
            }
            return handled
        }

        override fun fling(velocityX: Int) {
            // handled by snapTo()
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            activity.updateDots((l / activity.screenW.toFloat()).roundToInt())
        }
    }
}