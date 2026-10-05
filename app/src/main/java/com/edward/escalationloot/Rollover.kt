package com.edward.escalationloot

import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The single source of truth for when ProtoTrack's target loot changes.
 *
 * ProtoTrack's page states the rollover directly in its markup:
 *
 *   aria-label="Time until the next daily reset at 08:00 UTC"
 *   <span>Next reset · 08:00 UTC</span>
 *
 * and its own countdown script targets an exact instant with no grace period:
 *
 *   Date.UTC(y, m, d, 8, 0, 0)
 *
 * So 08:00:00 UTC is the real reset, which is 13:00 in Pakistan (PKT is a flat
 * UTC+5 with no DST). The UI counts down to exactly that instant.
 *
 * Fetching is a separate question from *displaying* the time. The page's rows
 * are rewritten as the rollover happens, so a fetch issued in the same
 * millisecond as the flip can read a half-updated table. That is what
 * FETCH_GRACE_MIN is for -- and it is deliberately small (2 minutes) because a
 * late fetch is a delay the user actually feels. A 10 minute margin meant the
 * countdown read "14m" while only "4m" remained, which is simply wrong.
 */
object Rollover {

    /** The true reset instant: 08:00 UTC, sharp. */
    const val HOUR_UTC = 8
    const val MINUTE_UTC = 0

    /**
     * How long after the flip to attempt a fetch, so we never read the page
     * mid-update. Kept small so the notification is not meaningfully late.
     */
    const val FETCH_GRACE_MIN = 2

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** Next rollover instant, in epoch millis, strictly in the future. */
    fun nextReset(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance(UTC).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, HOUR_UTC)
            set(Calendar.MINUTE, MINUTE_UTC)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var t = cal.timeInMillis
        // Exactly 24h in UTC. Adding a fixed 24h rather than recomputing
        // "tomorrow 08:00 local" keeps the instant correct in every timezone and
        // across daylight-saving changes -- local-time arithmetic would drift by
        // an hour twice a year in DST countries.
        if (t <= now) t += TimeUnit.DAYS.toMillis(1)
        return t
    }

    /** Next rollover instant, ignoring [FETCH_GRACE_MIN]. Used by the UI. */
    fun nextResetForDisplay(now: Long = System.currentTimeMillis()): Long = nextReset(now)

    /** Next fetch window: the rollover plus [FETCH_GRACE_MIN]. Used by the worker. */
    fun nextFetchWindow(now: Long = System.currentTimeMillis()): Long =
        nextReset(now) + TimeUnit.MINUTES.toMillis(FETCH_GRACE_MIN.toLong())

    /** Milliseconds from now until the next fetch window. */
    fun millisUntilNextFetch(now: Long = System.currentTimeMillis()): Long =
        nextFetchWindow(now) - now

    /**
     * True when it is still before the fetch window, i.e. an unchanged loot date
     * might just mean we ran early rather than that nothing changed.
     */
    fun beforeFetchWindow(now: Long = System.currentTimeMillis()): Boolean =
        now < nextFetchWindow(now)
}
