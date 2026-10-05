package com.edward.escalationloot

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Daily background check.
 *
 * Timing matters here. ProtoTrack rolls its target loot over at 08:00 UTC, which
 * its page advertises directly (see [Rollover]). A naive 24h periodic job
 * anchored to whenever the user first opened the app will fire at that same
 * wall-clock time every day, so if that time lands before 08:00 UTC the job reads
 * yesterday's data every single day and never notices a change.
 *
 * Two mechanisms prevent that:
 *   1. The first run is delayed to just after the rollover, and the 24h period
 *      carries that alignment forward on every subsequent run.
 *   2. If a run still sees an unchanged date (clock drift, OS batching the job
 *      to an awkward hour, or a fetch that raced the rollover), it retries a
 *      couple of times spread across the following hours instead of reporting
 *      success. Only genuinely stale data gives up.
 */
class LootWorker(app: Context, params: WorkerParameters) :
    CoroutineWorker(app, params) {

    override suspend fun doWork(): Result {
        val old = Storage.load(applicationContext)

        val fresh = try {
            ProtoTrack.fetch()
        } catch (e: Exception) {
            // Retry anything transient. A dead site gets exponential backoff
            // (30m, 1h, 2h, 4h) and eventually gives up, which is right: when
            // ProtoTrack recovers the next scheduled run picks the new loot up.
            return if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }

        if (old?.date != fresh.date) {
            Storage.save(applicationContext, fresh)
            Notify.show(applicationContext, I18n(zh = false), fresh)
            return Result.success()
        }

        // Same date as before. That is the common case for most days, so we must
        // not retry forever -- but we should retry when the rollover may simply
        // not have happened yet when we ran.
        return if (runAttemptCount < SAME_DATE_ATTEMPTS && mayHaveMissedRollover())
            Result.retry()
        else
            Result.success()
    }

    /**
     * True when it is still before the fetch window, meaning an unchanged loot
     * date might just mean we ran early rather than that nothing changed.
     */
    private fun mayHaveMissedRollover(): Boolean = Rollover.beforeFetchWindow()

    companion object {
        private const val WORK_NAME = "daily_escalation_check"
        private const val PREFS = "escalation_loot_sched"
        private const val KEY_FETCH_WINDOW_MIN = "fetch_window_min"

        /** The loader exposes a minutes-since-last-success for this. */
        private const val SAME_DATE_ATTEMPTS = 2
        private const val MAX_ATTEMPTS = 5

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<LootWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(millisUntilNextRollover(), TimeUnit.MILLISECONDS)
                // Only wake the radio when there is a connection to use.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES
                )
                .addTag("escalation_loot")
                .build()

            // KEEP, not UPDATE: UPDATE re-evaluates the initial delay on every
            // app launch, which would keep pushing the run further into the
            // future and could starve the job entirely.
            //
            // The flip side is that KEEP also means an app update which changes
            // the desired fetch time would never take effect. So we remember which
            // window we last scheduled and re-enqueue exactly once when it
            // changes. That happens on the upgrade path only -- every later launch
            // matches and stays on KEEP, so the 24h period is never restarted.
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val applied = prefs.getInt(KEY_FETCH_WINDOW_MIN, -1)
            val desired = Rollover.FETCH_GRACE_MIN
            val policy = if (applied == desired) {
                ExistingPeriodicWorkPolicy.KEEP
            } else {
                prefs.edit().putInt(KEY_FETCH_WINDOW_MIN, desired).apply()
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            }

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                policy,
                request
            )
        }

        /**
         * Milliseconds from now until the next fetch window, i.e. the 08:00 UTC
         * reset plus [Rollover.FETCH_GRACE_MIN]. Deliberately close to the true
         * reset so the notification is not meaningfully late.
         */
        fun millisUntilNextRollover(): Long = Rollover.millisUntilNextFetch()
    }
}

