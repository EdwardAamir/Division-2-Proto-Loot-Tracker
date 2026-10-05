package com.edward.escalationloot

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Live countdown to the next ProtoTrack loot rollover.
 *
 * Counts to the exact reset instant, 08:00 UTC (13:00 Pakistan), which is what
 * ProtoTrack itself advertises and what its own countdown script targets. See
 * [Rollover] for the details and for why the fetch grace is separate.
 *
 * All arithmetic is in UTC so the countdown is correct for any user in any
 * timezone; only the displayed clock time is localised.
 */
class Countdown {

    private var target: Long = Rollover.nextResetForDisplay()

    /** Milliseconds until the next rollover, always >= 0. */
    fun remainingMs(now: Long = System.currentTimeMillis()): Long {
        // Recompute when we have passed the boundary, so a countdown left
        // open overnight rolls over to the next day's target by itself.
        if (now >= target) target = Rollover.nextResetForDisplay(now)
        return target - now
    }

    /**
     * The reset moment rendered in the *device's* timezone, e.g. "1:10 PM" in
     * Pakistan or "4:10 PM" in China. The rollover is a fixed instant in UTC
     * (08:10 UTC), so every user sees the same moment expressed in their own
     * local clock.
     */
    fun targetLocalLabel(now: Long = System.currentTimeMillis()): String {
        remainingMs(now) // refreshes `target` if we crossed the boundary
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.timeInMillis = target
        val localHour = c.get(Calendar.HOUR_OF_DAY)
        val minute = c.get(Calendar.MINUTE)
        val ampm = if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val h12 = when {
            localHour == 0 -> 12
            localHour > 12 -> localHour - 12
            else -> localHour
        }
        return String.format(Locale.US, "%d:%02d %s", h12, minute, ampm)
    }

    /**
     * Short, readable zone name for display.
     *
     * Prefers the real abbreviation from Android's ICU data ("PKT", "CST",
     * "JST"), because that is what someone in that country actually calls it.
     * Zones with no abbreviation (many of Asia) come back from ICU as
     * "GMT+5" style strings, which we rewrite to "UTC+5".
     */
    fun zoneLabel(): String {
        val icu = runCatching {
            android.icu.util.TimeZone.getDefault()
                .getDisplayName(
                    false,
                    android.icu.util.TimeZone.SHORT,
                    android.icu.util.ULocale.getDefault()
                )
        }.getOrNull()
        if (icu.usable()) return icu!!

        val tz = TimeZone.getDefault()
        val short = runCatching { tz.getDisplayName(false, TimeZone.SHORT) }.getOrNull()
        if (short.usable()) return short!!

        // Last resort: spell out the UTC offset.
        val offsetMinutes = tz.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMinutes)
        return if (abs % 60 == 0) {
            String.format(Locale.US, "UTC%s%d", sign, abs / 60)
        } else {
            String.format(Locale.US, "UTC%s%d:%02d", sign, abs / 60, abs % 60)
        }
    }

    /** True when a zone name carries real information rather than a GMT offset. */
    private fun String?.usable(): Boolean =
        !isNullOrBlank() && !startsWith("GMT") && !startsWith("UTC+") && !startsWith("UTC-")

}
