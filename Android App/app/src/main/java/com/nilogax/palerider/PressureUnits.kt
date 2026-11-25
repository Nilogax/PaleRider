package com.nilogax.palerider.data

import android.content.Context

enum class PressureUnit(val label: String) {
    PSI("psi"),
    BAR("bar");

    /** Formats a pressure given in psi in this unit, with no unit suffix. */
    fun format(psi: Float?): String = when (this) {
        PSI -> psi?.let { String.format(java.util.Locale.UK, "%.1f", it) } ?: "--.-"
        BAR -> psi?.let { String.format(java.util.Locale.UK, "%.2f", it * OutriderConfig.PSI_TO_BAR) } ?: "-.--"
    }
}

/**
 * The chosen display unit. Stored in SharedPreferences so the notification (which can be
 * posted with no UI running) uses the same unit as the screen.
 */
object UnitPrefs {

    private const val PREFS = "palerider_settings"
    private const val KEY_UNIT = "pressure_unit"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): PressureUnit =
        prefs(context).getString(KEY_UNIT, null)
            ?.let { name -> PressureUnit.values().firstOrNull { it.name == name } }
            ?: PressureUnit.PSI

    fun set(context: Context, unit: PressureUnit) {
        prefs(context).edit().putString(KEY_UNIT, unit.name).apply()
        TyreRepository.setUnit(unit)
    }

    /** Copies the saved unit into TyreRepository for the UI. Call when the app starts. */
    fun syncToRepository(context: Context) {
        TyreRepository.setUnit(get(context))
    }
}

object HoldDurationPrefs {
    private const val PREFS = "palerider_hold_duration"
    private const val KEY_DURATION = "hold_duration_minutes"
    const val DEFAULT_MINUTES = 5

    fun get(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DURATION, DEFAULT_MINUTES)
    }

    fun set(context: Context, minutes: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DURATION, minutes.coerceIn(1, 60)).apply()
    }
}

/**
 * Minimum time between pressure notifications for the same bike. The last notification
 * time is kept per bike in SharedPreferences so it survives the process being killed.
 */
object NotificationGapPrefs {
    private const val PREFS = "palerider_notification_gap"
    private const val KEY_GAP_MINUTES = "gap_minutes"
    private const val KEY_LAST_PREFIX = "last_notified_"

    const val DEFAULT_MINUTES = 30
    const val MAX_MINUTES = 23 * 60 + 59

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMinutes(context: Context): Int =
        prefs(context).getInt(KEY_GAP_MINUTES, DEFAULT_MINUTES).coerceIn(0, MAX_MINUTES)

    fun setMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_GAP_MINUTES, minutes.coerceIn(0, MAX_MINUTES)).apply()
    }

    fun recordNotified(context: Context, bikeId: String) {
        prefs(context).edit().putLong(KEY_LAST_PREFIX + bikeId, System.currentTimeMillis()).apply()
    }

    /** True if this bike was notified less than the chosen gap ago. A gap of 0 never blocks. */
    fun isWithinGap(context: Context, bikeId: String, nowMillis: Long): Boolean {
        val gapMillis = getMinutes(context) * 60_000L
        if (gapMillis <= 0L) return false
        val last = prefs(context).getLong(KEY_LAST_PREFIX + bikeId, 0L)
        // A last time in the future means the clock moved; don't block on that
        return last > 0L && nowMillis >= last && nowMillis - last < gapMillis
    }

    fun format(totalMinutes: Int): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            totalMinutes == 0 -> "No gap"
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}