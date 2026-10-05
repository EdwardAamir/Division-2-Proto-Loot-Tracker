package com.edward.escalationloot

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var i18n: I18n
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var dateRow: TextView
    private lateinit var lang: TextView
    private lateinit var refresh: TextView
    private lateinit var footer: TextView
    private lateinit var countdownView: TextView
    private val countdown = Countdown()

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            countdownView.postDelayed(this, 1000L)
        }
    }

    /**
     * Renders the countdown line in the device's own timezone. The remaining
     * duration is identical worldwide (it is the same instant); the clock time
     * and zone label are what make it local, so a user in China reads 4:10 PM
     * where a user in Pakistan reads 1:10 PM.
     */
    private fun updateCountdown() {
        val now = System.currentTimeMillis()
        countdownView.text = i18n.countdownText(
            countdown.remainingMs(now),
            countdown.targetLocalLabel(now),
            countdown.zoneLabel()
        )
    }

    private var loading = false
    private var fetchFailed = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)

        i18n = I18n(zh = b?.getBoolean(KEY_ZH) ?: false)
        content = findViewById(R.id.content)
        status = findViewById(R.id.status)
        dateRow = findViewById(R.id.dateRow)
        lang = findViewById(R.id.langButton)
        refresh = findViewById(R.id.refreshButton)
        footer = findViewById(R.id.footer)
        countdownView = findViewById(R.id.countdown)

        findViewById<TextView>(R.id.title).text = i18n.appTitle
        findViewById<TextView>(R.id.subtitle).text = i18n.subtitle
        lang.setOnClickListener { toggleLang() }
        refresh.setOnClickListener { load(manual = true) }

        // Data-source credit. Tappable, and handled explicitly rather than left
        // to autoLink: this points at someone else's site, so it should open in
        // the browser and never be treated as an in-app destination.
        findViewById<TextView>(R.id.footerCredit).apply {
            movementMethod = LinkMovementMethod.getInstance()
            setOnClickListener { openSource() }
        }

        applyLabels()
        render(Storage.load(this))
        LootWorker.schedule(this)

        // Refresh on open whenever the cached day is not today's loot date, so the
        // app is never stale on screen. See needsRefresh() for the rules.
        val cached = Storage.load(this)
        if (cached == null || needsRefresh(cached)) load(manual = false)
        else {
            maybeRequestNotificationPermission()
            if (isStale(cached)) status.text = i18n.stale
        }
    }

    /**
     * True when the cached data cannot be trusted for the current loot date.
     *
     * We deliberately do not compare against the wall clock: a stale-looking
     * entry is exactly what we want to re-fetch. Instead we re-fetch when
     *   - we have no data at all, or
     *   - the site has already rolled over (after 08:10 UTC) and our cached
     *     date is from before today, or
     *   - the fetch we did last time failed.
     */
    private fun needsRefresh(day: LootDay?): Boolean {
        if (day == null) return true
        if (fetchFailed) return true
        if (!isStale(day)) return false
        return pastRolloverUtc()
    }

    /** Cached date is not today's date in UTC. */
    private fun isStale(day: LootDay): Boolean {
        val iso = day.dateIso ?: return true
        return iso != todayIsoUtc()
    }

    private fun pastRolloverUtc(): Boolean =
        LootWorker.millisUntilNextRollover() <
            java.util.concurrent.TimeUnit.DAYS.toMillis(1)

    private fun todayIsoUtc(): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    override fun onStart() {
        super.onStart()
        countdownView.removeCallbacks(ticker)
        countdownView.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        // Stop the 1s ticker while backgrounded so we do not keep the screen
        // awake or burn battery when nobody is looking at the countdown.
        countdownView.removeCallbacks(ticker)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ZH, i18n.zh)
    }

    private fun toggleLang() {
        i18n = I18n(!i18n.zh)
        applyLabels()
        updateCountdown()
        render(Storage.load(this))
    }

    private fun applyLabels() {
        lang.text = i18n.langToggle
        refresh.text = if (loading) i18n.refreshing else i18n.refresh
        footer.text = i18n.sources
        // Both new footer lines, so they follow the language toggle like every
        // other label rather than staying English in a Chinese UI.
        findViewById<TextView>(R.id.footerCredit).text = i18n.dataCredit
        findViewById<TextView>(R.id.footerDisclaimer).text = i18n.notAffiliated
        refresh.isEnabled = !loading
        refresh.alpha = if (loading) 0.6f else 1f
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5
            )
        }
    }

    private fun load(manual: Boolean) {
        if (loading) return
        loading = true
        applyLabels()
        if (manual) status.text = i18n.loading

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ProtoTrack.fetch() } }
            result
                .onSuccess { fresh ->
                    fetchFailed = false
                    val old = Storage.load(this@MainActivity)
                    val changed = old?.date != fresh.date
                    Storage.save(this@MainActivity, fresh)
                    render(fresh)
                    // Always notify on a real date change, including when the user
                    // opened the app and it auto-refreshed: that is exactly the
                    // moment they want to be told. A manual tap on a day whose data
                    // is already current produces no notification, so there is no
                    // duplicate spam from re-opening the app.
                    if (changed) Notify.show(this@MainActivity, i18n, fresh)
                    maybeRequestNotificationPermission()
                }
                .onFailure { err ->
                    fetchFailed = true
                    status.text = describeFailure(err)
                    status.setTextColor(getColor(R.color.kind_weapon))
                    if (Storage.load(this@MainActivity) == null) render(null)
                }
            loading = false
            applyLabels()
        }
    }

    /**
     * Opens the data source in the user's browser.
     *
     * FAILSAFE rather than a bare startActivity: if no browser is installed the
     * app would otherwise crash from a tap on a credit line, which is a bad way
     * to fail on the one element that is purely informational.
     */
    private fun openSource() {
        val uri = Uri.parse(SOURCE_URL)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /**
     * Turns a failure into something a user can act on.
     *
     * ProtoTrack has gone down before (it served HTTP 500 for an extended
     * period), so when the site itself is the problem we say so. Showing a bare
     * "timeout" for a 500 makes people check their own connection for no reason.
     */
    private fun describeFailure(err: Throwable): String {
        val cached = Storage.load(this@MainActivity)
        val staleNote = if (cached != null) " · ${i18n.showingCached}" else ""

        val cause = generateSequence(err) { it.cause }.last()
        return when {
            cause is ProtoTrack.UpstreamException ->
                "${i18n.siteDown} (${cause.message})$staleNote"

            cause is java.net.SocketTimeoutException ->
                "${i18n.noConnection}$staleNote"

            cause is java.net.UnknownHostException ->
                "${i18n.noConnection}$staleNote"

            cause is javax.net.ssl.SSLException ->
                "${i18n.noConnection}$staleNote"

            // A parse failure on a 200 usually means the page layout changed,
            // which is our problem to report rather than the user's to fix.
            else -> "${i18n.updateFailed}: ${cause.message ?: cause.javaClass.simpleName}$staleNote"
        }
    }

    private fun render(day: LootDay?) {
        content.removeAllViews()

        if (day == null) {
            dateRow.text = ""
            val empty = LayoutInflater.from(this)
                .inflate(R.layout.activity_empty, content, false)
            empty.findViewById<TextView>(R.id.emptyTitle).text = i18n.empty
            empty.findViewById<TextView>(R.id.emptyHint).text = i18n.emptyHint
            content.addView(empty)
            return
        }

        // ── Header: "TODAY · Sun 04 Oct 2026"
        val weekday = i18n.weekdayOf(day.dateIso)
        val badge = if (day.isToday) "${i18n.today}  " else ""
        dateRow.text = buildString {
            append(badge)
            if (weekday != null) append("$weekday  ")
            append(day.date)
        }
        status.setTextColor(getColor(R.color.text_muted))
        status.text = "${day.entries.size} ${i18n.missions}"

        day.entries.forEachIndexed { idx, e -> content.addView(card(idx, e)) }
    }

    private fun card(idx: Int, e: LootEntry): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_loot, content, false)
        val accent = getColor(kindColor(e.kind))

        v.findViewById<TextView>(R.id.indexBadge).text = (idx + 1).toString()
        v.findViewById<TextView>(R.id.mission).text =
            if (i18n.zh) e.missionZh else e.missionEn
        v.findViewById<TextView>(R.id.loot).text =
            if (i18n.zh) e.lootZh else e.lootEn
        v.findViewById<TextView>(R.id.loot).setTextColor(accent)

        v.findViewById<TextView>(R.id.kindChip).apply {
            text = i18n.kind(e.kind)
            backgroundTintList = ColorStateList.valueOf(withAlpha(accent, 38))
            setTextColor(accent)
        }

        // Faction strip + icon
        v.findViewById<View>(R.id.factionStrip).setBackgroundColor(factionColor(e.factionEn))
        Images.load(v.findViewById<ImageView>(R.id.factionIcon), e.factionIconUrl)

        // Gear icon: this is the whole point of the card. Candidate URLs are
        // tried in order because the JSON feed gives a name, not an asset path.
        val lootIcon = v.findViewById<ImageView>(R.id.lootIcon)
        Images.load(
            lootIcon,
            listOfNotNull(e.imageUrl) + e.imageUrlFallbacks,
            R.drawable.ic_loot_placeholder
        )

        // Loot history strip
        val history = v.findViewById<View>(R.id.historyRow)
        val hasHistory = e.appearances != null || !e.lastSeen.isNullOrBlank()
        history.visibility = if (hasHistory) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.appearancesLabel).text = i18n.appearances
        v.findViewById<TextView>(R.id.lastSeenLabel).text = i18n.lastSeen
        v.findViewById<TextView>(R.id.appearances).text =
            e.appearances?.toString() ?: i18n.neverSeen
        v.findViewById<TextView>(R.id.lastSeen).text =
            e.lastSeen ?: i18n.neverSeen

        return v
    }

    private fun kindColor(k: LootKind) = when (k) {
        LootKind.WEAPON -> R.color.kind_weapon
        LootKind.GEAR -> R.color.kind_gear
        LootKind.GEARSET -> R.color.kind_gearset
        LootKind.BRANDSET -> R.color.kind_brandset
        LootKind.OTHER -> R.color.kind_other
    }

    private fun factionColor(faction: String?) = when (faction?.lowercase()) {
        "rikers" -> getColor(R.color.faction_rikers)
        "truesons" -> getColor(R.color.faction_truesons)
        "outcasts" -> getColor(R.color.faction_outcasts)
        else -> getColor(R.color.card_accent)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    companion object {
        private const val KEY_ZH = "zh"

        /**
         * The data source, linked from the footer credit.
         *
         * Points at the specific page the app reads, not just the domain, so the
         * credit tells someone exactly which source was used.
         */
        private const val SOURCE_URL = "https://prototrack.gg/target-loot/target-loot.php"
    }
}
