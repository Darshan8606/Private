package com.reelcounter

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.time.Duration
import java.time.LocalDateTime

/**
 * Detection strategy (see INSTALL_GUIDE.md for the honest limitations):
 *
 *  1. Instagram exposes no "reel changed" event, so we look at the accessibility tree.
 *     The Reels viewer is a vertical pager whose view id contains "clips_viewer".
 *     If that pager is on screen we are "in Reels"; otherwise (home feed, DMs, profile,
 *     search ...) we are not, and the overlay is hidden and nothing is counted.
 *  2. Inside the pager we pick the page that fills >= 80% of the pager. While a swipe is
 *     in progress no page does, so mid-swipe states are ignored.
 *  3. We build an in-memory fingerprint (hash) of that page from the author / caption /
 *     audio labels only (NOT like counts, so liking does not change it). A new fingerprint
 *     that we have not seen among the last reels = the user moved to a new Reel = +1.
 *     Likes, shares, comments, pausing and opening/closing the comment sheet do not change
 *     the fingerprint. Scrolling back to an already-counted reel does not count again.
 *  4. The first reel shown when entering Reels is not counted; only transitions are.
 *
 * Only a number-hash is kept in memory. No text is stored, logged or transmitted.
 */
class ReelAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var overlay: TextView? = null
    private var overlayShown = false

    private var lastSig: Int? = null
    private val recent = ArrayDeque<Int>()
    private var evalScheduled = false

    private val evalRunnable = Runnable { evalScheduled = false; evaluate() }
    private val midnightRunnable = Runnable { refreshText(); scheduleMidnight() }

    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshText(); scheduleMidnight()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == CounterStore.KEY_ENABLED && !CounterStore.isEnabled(this)) {
            hideOverlay(); lastSig = null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        CounterStore.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        registerReceiver(dateReceiver, f)
        scheduleMidnight()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!CounterStore.isEnabled(this)) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg == INSTAGRAM) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleEvaluate()
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !isIgnorable(pkg)) {
            // Another app (or the launcher) came to the foreground.
            hideOverlay(); lastSig = null
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        try { unregisterReceiver(dateReceiver) } catch (_: Exception) {}
        CounterStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    private fun isIgnorable(pkg: String): Boolean =
        pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("honeyboard")

    // Throttle (not debounce): a playing reel emits constant content changes.
    private fun scheduleEvaluate() {
        if (evalScheduled) return
        evalScheduled = true
        handler.postDelayed(evalRunnable, 150)
    }

    private fun evaluate() {
        if (!CounterStore.isEnabled(this)) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != INSTAGRAM) return

        val pager = findPager(root)
        if (pager == null || !pager.isVisibleToUser) {
            hideOverlay(); lastSig = null
            return
        }
        showOverlay()

        val sig = currentReelSignature(pager) ?: return // mid-swipe or unreadable: do nothing
        val prev = lastSig
        if (prev == null) { lastSig = sig; remember(sig); return } // entering Reels: no count
        if (sig == prev) return
        lastSig = sig
        if (!recent.contains(sig)) {
            remember(sig)
            CounterStore.increment(this)
            refreshText()
        }
    }

    private fun remember(sig: Int) {
        recent.addLast(sig)
        while (recent.size > 40) recent.removeFirst()
    }

    private fun findPager(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 4000) {
            val n = stack.removeLast()
            visited++
            val id = n.viewIdResourceName
            if (id != null && id.contains("clips_viewer")) {
                if (id.endsWith("clips_viewer_view_pager")) return n
                val cls = n.className?.toString() ?: ""
                if (fallback == null && (cls.contains("RecyclerView") || cls.contains("ViewPager"))) {
                    fallback = n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        return fallback
    }

    private fun currentReelSignature(pager: AccessibilityNodeInfo): Int? {
        val pb = Rect(); pager.getBoundsInScreen(pb)
        val ph = pb.height()
        if (ph <= 0) return null

        var best: AccessibilityNodeInfo? = null
        var bestVisible = 0
        for (i in 0 until pager.childCount) {
            val c = pager.getChild(i) ?: continue
            val r = Rect(); c.getBoundsInScreen(r)
            val inter = Rect()
            if (!inter.setIntersect(r, pb)) continue
            if (inter.height() > bestVisible) { bestVisible = inter.height(); best = c }
        }
        if (best == null || bestVisible < ph * 0.8) return null

        val keyed = StringBuilder()
        val loose = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(best)
        var visited = 0
        while (stack.isNotEmpty() && visited < 800) {
            val n = stack.removeLast()
            visited++
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                val id = n.viewIdResourceName?.lowercase() ?: ""
                if (KEY_HINTS.any { id.contains(it) }) keyed.append(t).append('|')
                else if (t.length >= 12 && !looksLikeCount(t)) loose.append(t).append('|')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        val s = if (keyed.isNotEmpty()) keyed.toString() else loose.toString()
        return if (s.isEmpty()) null else s.hashCode()
    }

    private fun looksLikeCount(t: String): Boolean =
        t.all { it.isDigit() || it in ",.KkMm " } || t.contains(" likes") || t.contains(" comments")

    // ---------- overlay (drawn as an accessibility overlay: no extra permission needed) ----------

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun showOverlay() {
        if (overlay == null) {
            overlay = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                background = GradientDrawable().apply {
                    setColor(Color.argb(150, 0, 0, 0))
                    cornerRadius = dp(14f).toFloat()
                }
            }
        }
        refreshText()
        if (!overlayShown) {
            val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBar = if (sbId > 0) resources.getDimensionPixelSize(sbId) else dp(24f)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(10f)
                y = statusBar + dp(6f)
            }
            try { wm.addView(overlay, lp); overlayShown = true } catch (_: Exception) {}
        }
    }

    private fun hideOverlay() {
        if (overlayShown) {
            try { wm.removeView(overlay) } catch (_: Exception) {}
            overlayShown = false
        }
    }

    private fun refreshText() {
        overlay?.text = "\u25B6 ${CounterStore.getCount(this)}"
    }

    private fun scheduleMidnight() {
        handler.removeCallbacks(midnightRunnable)
        val now = LocalDateTime.now()
        val next = now.toLocalDate().plusDays(1).atStartOfDay()
        handler.postDelayed(midnightRunnable, Duration.between(now, next).toMillis() + 500)
    }

    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private val KEY_HINTS = listOf("author", "username", "caption", "audio", "music", "clips_title")
    }
}
