package com.reelcounter

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate

/**
 * Persistent storage for the toggle and today's count.
 * The count is stored together with the local date it belongs to. Every read checks the
 * date, so the reset at 00:00 happens by itself, even if no app was running at midnight,
 * and it survives restarts of the phone, Instagram and this app.
 */
object CounterStore {
    private const val PREFS = "reel_counter"
    const val KEY_ENABLED = "enabled"
    private const val KEY_DATE = "date"
    private const val KEY_COUNT = "count"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @Synchronized
    fun getCount(ctx: Context): Int {
        val p = prefs(ctx)
        rollIfNewDay(p)
        return p.getInt(KEY_COUNT, 0)
    }

    @Synchronized
    fun increment(ctx: Context): Int {
        val p = prefs(ctx)
        rollIfNewDay(p)
        val next = p.getInt(KEY_COUNT, 0) + 1
        p.edit().putInt(KEY_COUNT, next).apply()
        return next
    }

    private fun rollIfNewDay(p: SharedPreferences) {
        val today = LocalDate.now().toString() // device local date
        if (p.getString(KEY_DATE, null) != today) {
            p.edit().putString(KEY_DATE, today).putInt(KEY_COUNT, 0).apply()
        }
    }
}
