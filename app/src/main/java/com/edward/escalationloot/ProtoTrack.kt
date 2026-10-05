package com.edward.escalationloot

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Scrapes https://prototrack.gg/target-loot/target-loot.php
 *
 * Page structure (verified against the live site):
 *   div.loot-info-bar
 *     div.loot-info-field > span "Loot date" + strong > time[datetime]
 *   table.tl-overview-table  (thead: Missions | Target Loot | Loot History)
 *     tbody > tr
 *       td[data-label=Mission]  div.tl-mission > img.tl-faction-icon + span
 *       td[data-label="Target Loot"] div.tl-loot > img[data-loot] + span
 *       td[data-label="Loot History"] div.tl-history > span/strong pairs
 */
object ProtoTrack {
    private const val BASE = "https://prototrack.gg"
    private const val URL = "$BASE/target-loot/target-loot.php"

    /**
     * Machine-readable mirror of the same data, and the authoritative feed.
     *
     * This is the file the ProtoTrack Discord bot reads, which is why the bot
     * posts the new loot instantly. The PHP page is a separate, slower job and
     * has been returning HTTP 500 / hanging, so it is treated as decoration
     * rather than as the data: see [fetch].
     */
    private const val JSON_URL = "$BASE/target-loot/target-loot.json"

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Separate client for the optional HTML decoration fetch.
     *
     * Split from [client] because the two have opposite timeout needs: the
     * authoritative JSON feed gets a generous 30s, while the PHP page only ever
     * adds decoration and must not delay showing data the app already has.
     */
    private val decorationClient = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    /**
     * Raised when ProtoTrack itself is unreachable or broken, as opposed to the
     * app being at fault. The message is chosen to be shown to the user, so it
     * must not say "timeout" when the server actually answered with an error --
     * that sends people hunting for a Wi-Fi problem that does not exist.
     */
    class UpstreamException(message: String) : Exception(message)

    /**
     * The JSON feed is the source of truth; the HTML page only adds detail.
     *
     * This is the ordering the user asked for: accurate data like the Discord
     * bot, which reads [JSON_URL] and therefore updates the instant the new loot
     * is published, over a prettier but stale HTML page. Concretely:
     *
     *   1. Fetch the JSON. That decides the date, the missions and the loot.
     *   2. Fetch the HTML separately, and merge it in *only if its date matches
     *      the JSON's date*. That is what makes the extra detail safe: the HTML
     *      can contribute faction icons and loot history, but it can never
     *      override what the authoritative feed says.
     *   3. If the JSON fails outright, fall back to the HTML page alone so the
     *      app still shows something.
     *
     * The date guard in step 2 matters. The PHP page and the JSON are generated
     * by different jobs, so during the rollover window they can disagree: the
     * page may still be serving yesterday's table with a 200 while the JSON has
     * already moved on. Merging blindly in that window is exactly how you end
     * up showing a correct date next to the wrong loot.
     */
    fun fetch(): LootDay {
        val jsonDay = runCatching { fetchFromJson() }.getOrNull()
        if (jsonDay != null) return enrichFromHtml(jsonDay)

        // No authoritative feed. The HTML page is all that is left, so use it
        // rather than showing nothing, and let its own failure be the error.
        return fetchFromHtml()
    }

    /**
     * Add the HTML page's extra detail to a JSON-derived day, keeping the JSON's
     * date, missions and loot untouched. Silently returns [day] unchanged if the
     * page is down, fails to parse, or is describing a different day.
     */
    private fun enrichFromHtml(day: LootDay): LootDay {
        // The decoration request must never hold up the real data. The PHP page
        // can hang rather than refuse, so it gets its own short timeout; if the
        // authoritative JSON already succeeded we have what the user needs and
        // the extra detail is not worth a visible wait for.
        val html = runCatching {
            val req = Request.Builder()
                .url(URL)
                .header("User-Agent", "Mozilla/5.0 EscalationLootApp/1.0")
                .build()
            // 4s is enough for the page when it is healthy (it serves in well
            // under a second) and short enough that a hang is unnoticeable.
            decorationClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw UpstreamException("HTTP ${response.code}")
                response.body?.string() ?: throw UpstreamException("Empty response")
            }
        }.getOrNull() ?: return day

        return runCatching { mergeDetail(day, parseHtml(html)) }.getOrDefault(day)
    }

    /**
     * Fold the HTML page's extra fields into the JSON-derived day.
     *
     * The date guard is the safety property here, so it lives in this pure
     * function rather than being buried in the fetch path, which makes it
     * directly testable. Split out from [enrichFromHtml] for that reason.
     */
    internal fun mergeDetail(jsonDay: LootDay, htmlDay: LootDay): LootDay {
        // Refuse to enrich across days: mismatched content is stale by definition.
        if (!sameDay(jsonDay.dateIso, htmlDay.dateIso)) return jsonDay

        // The two feeds are produced by separate jobs, so match on a normalised
        // mission name rather than assuming byte-identical text.
        val byMission = htmlDay.entries.associateBy { normaliseMission(it.missionEn) }
        val merged = jsonDay.entries.map { jsonEntry ->
            val extra = byMission[normaliseMission(jsonEntry.missionEn)] ?: return@map jsonEntry
            jsonEntry.copy(
                // The HTML page knows the real asset path, so prefer it over the
                // reconstructed candidates. Keep the candidates as fallbacks in
                // case the site ever moves a file.
                imageUrl = extra.imageUrl ?: jsonEntry.imageUrl,
                imageUrlFallbacks = if (extra.imageUrl != null) {
                    listOfNotNull(jsonEntry.imageUrl) + jsonEntry.imageUrlFallbacks
                } else {
                    jsonEntry.imageUrlFallbacks
                },
                // Only the HTML page carries these; null on the JSON path.
                factionIconUrl = extra.factionIconUrl,
                factionEn = extra.factionEn ?: jsonEntry.factionEn,
                appearances = extra.appearances ?: jsonEntry.appearances,
                lastSeen = extra.lastSeen ?: jsonEntry.lastSeen
            )
        }
        return jsonDay.copy(entries = merged)
    }

    /**
     * True when two ISO dates denote the same day. A null on either side is not
     * a match: without a date to compare we cannot prove the pages agree, and
     * the whole point of the guard is to only merge when they provably do.
     */
    private fun sameDay(a: String?, b: String?): Boolean =
        a != null && b != null && a.trim() == b.trim()

    /** Mission names are matched case- and whitespace-insensitively. */
    private fun normaliseMission(name: String): String =
        name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun fetchFromHtml(): LootDay = parseHtml(get(URL))

    /** Pure parse step, separated from the HTTP call so it is testable. */
    internal fun parseHtml(html: String): LootDay {
        val doc = Jsoup.parse(html)

        val dateText = readLootDate(doc) ?: error("Loot date not found")
        val dateIso = readLootDateIso(doc)

        val entries = parseRows(doc)
        if (entries.isEmpty()) error("Escalation mission table not found")

        return LootDay(
            date = dateText,
            entries = entries,
            dateIso = dateIso,
            isToday = dateIso?.let { isoToday(it) } ?: false
        )
    }

    /**
     * GET with status checking.
     *
     * A 500 from the PHP page is a tiny Apache error page; parsing it would
     * surface as a confusing "loot date not found" rather than the real cause.
     */
    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 EscalationLootApp/1.0")
            .build()
        return client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpstreamException("ProtoTrack returned HTTP ${response.code}")
            }
            response.body?.string() ?: throw UpstreamException("Empty ProtoTrack response")
        }
    }

    /**
     * The authoritative fetch: mission, loot, category, priority flag and date.
     *
     * This path carries no icons or history, so icon URLs are reconstructed from
     * the site's asset naming convention, which the JSON does not spell out
     * (see [iconCandidates]). It is best-effort by design: a name that does not
     * follow the convention degrades to a placeholder instead of failing the
     * fetch, and [enrichFromHtml] replaces the guess with the real path whenever
     * the HTML page is reachable.
     */
    private fun fetchFromJson(): LootDay {
        val body = get(JSON_URL)
        val root = JsonParser.parseString(body).asJsonObject

        val dateIso = root.get("date")?.asString?.trim()
        val updatedAt = root.get("updated_at")?.asString?.trim()

        val missions = root.getAsJsonArray("missions")
            ?: throw UpstreamException("JSON has no missions array")

        val entries = mutableListOf<LootEntry>()
        missions.forEach { el ->
            val obj = el.asJsonObject
            val mission = obj.get("name")?.asString?.trim().orEmpty()
            val loot = obj.get("loot")?.asString?.trim().orEmpty()
            if (mission.isEmpty() || loot.isEmpty()) return@forEach

            val category = obj.get("category")?.asString?.trim().orEmpty()
            val kind = kindFromCategory(category)

            val icons = iconCandidates(kind, loot)

            entries += LootEntry(
                missionEn = mission,
                // ProtoTrack publishes English only, so there is no zh source.
                missionZh = mission,
                lootEn = loot,
                lootZh = loot,
                imageUrl = icons.firstOrNull(),
                imageUrlFallbacks = icons.drop(1),
                kind = kind,
                factionIconUrl = null,
                factionEn = null,
                appearances = null,
                lastSeen = null,
                priorityTargetLoot = obj.get("priority_target_loot")?.asBoolean ?: false
            )
        }
        if (entries.isEmpty()) throw UpstreamException("JSON contained no usable missions")

        return LootDay(
            date = formatDate(dateIso, updatedAt),
            entries = entries,
            dateIso = dateIso,
            isToday = dateIso?.let { isoToday(it) } ?: false
        )
    }

    /** "Gear" -> gear, "Gear Set" -> gearsets, "Brand Set" -> brandsets. */
    private fun kindFromCategory(category: String): LootKind = when {
        category.equals("Weapon", true) -> LootKind.WEAPON
        category.equals("Gear", true) -> LootKind.GEAR
        category.equals("Gear Set", true) -> LootKind.GEARSET
        category.equals("Brand Set", true) -> LootKind.BRANDSET
        else -> LootKind.OTHER
    }

    /**
     * Candidate icon URLs for a loot name, most likely first.
     *
     * The JSON feed supplies a display name, never an asset path, so the path has
     * to be reconstructed. The convention is folder = category and file = name
     * lowercased with spaces removed, but it is not exact: ProtoTrack serves
     * "Gear Mods" from gear/gearmod.png -- singular -- while the naive slug
     * gear/gearmods.png returns 404. Verified against the live server:
     *
     *   Mask                  gear/mask.png                 200
     *   Negotiators Dilemma   gearsets/negotiatorsdilemma.png  200
     *   Gear Mods             gear/gearmod.png  (not gearmods)  200
     *   Hotshot               gearsets/hotshot.png          200
     *   Badger Tuff           brandsets/badgertuff.png      200
     *
     * So we offer a few variants and let [Images] fall through the 404s. Extra
     * candidates are tried in a fixed order and cached after the first hit, so
     * the cost is paid once.
     */
    private fun iconCandidates(kind: LootKind, loot: String): List<String> {
        if (kind == LootKind.OTHER) return emptyList()
        val slug = loot.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        if (slug.isEmpty()) return emptyList()

        val dir = "$BASE/assets/division2/${kind.wire}"
        val variants = buildList {
            add(slug)
            // Plural display name, singular file: "Gear Mods" -> gearmod.
            if (slug.endsWith("s") && slug.length > 3) add(slug.dropLast(1))
            // Singular display name, plural file.
            if (!slug.endsWith("s")) add(slug + "s")
            // "The X" style prefixes are usually dropped.
            if (slug.startsWith("the")) add(slug.removePrefix("the"))
        }
        return variants.distinct().map { "$dir/$it.png" }
    }

    /** "2026-10-05" -> "05 Oct 2026", matching the HTML page's date format. */
    private fun formatDate(iso: String?, updatedAt: String?): String {
        if (iso != null) {
            val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(iso)
            if (m != null) {
                val (y, mo, d) = m.destructured
                val monthNames = arrayOf(
                    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
                )
                val monthIndex = mo.toIntOrNull()?.minus(1) ?: return iso
                val month = monthNames.getOrNull(monthIndex) ?: return iso
                return "$d $month $y"
            }
        }
        return updatedAt ?: ""
    }

    /** "04 Oct 2026" from the loot-info-bar. */
    private fun readLootDate(doc: Document): String? {
        val field = doc.selectFirst("div.loot-info-field")
            ?.takeIf {
                it.selectFirst("span")?.text()?.trim()
                    ?.equals("Loot date", ignoreCase = true) == true
            }
            ?: doc.select("div.loot-info-field")
                .firstOrNull {
                    it.text().trim().startsWith("Loot date", ignoreCase = true)
                }
            ?: return null

        val time = field.selectFirst("time")
        val fromTime = time?.text()?.trim()
        if (!fromTime.isNullOrEmpty()) return fromTime

        // Fall back to stripping the "Loot date" label from the field's text.
        return field.text().trim()
            .removePrefix("Loot date")
            .trim()
            .removePrefix(":")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /** ISO date from time[datetime], e.g. "2026-10-04". */
    private fun readLootDateIso(doc: Document): String? {
        val field = doc.select("div.loot-info-field").firstOrNull {
            it.selectFirst("span")?.text()?.trim()
                ?.equals("Loot date", ignoreCase = true) == true
        } ?: return null
        return field.selectFirst("time")?.attr("datetime")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseRows(doc: Document): List<LootEntry> {
        val table = doc.selectFirst("table.tl-overview-table") ?: firstTableWithLootHeaders(doc)
        val rows = table?.select("tbody tr") ?: table?.select("tr").orEmpty()

        val out = mutableListOf<LootEntry>()
        for (row in rows) {
            val missionCell = row.selectFirst("td[data-label=Mission]") ?: continue
            val lootCell = row.selectFirst("td[data-label=\"Target Loot\"]") ?: continue

            val mission = missionCell.selectFirst("span")?.text()?.trim().orEmpty()
            val loot = lootCell.selectFirst("span")?.text()?.trim()
                ?: lootCell.selectFirst("img")?.attr("data-loot")?.trim().orEmpty()
            if (mission.isEmpty() || loot.isEmpty()) continue

            val lootImg = lootCell.selectFirst("div.tl-loot img") ?: lootCell.selectFirst("img")
            val imgUrl = lootImg?.let { absolutize(it.attr("src")) }

            // Category from the asset folder: .../weapons/ar.png -> WEAPON
            val kind = imgUrl?.let { LootKind.from(assetFolder(it)) } ?: LootKind.OTHER

            val factionImg = missionCell.selectFirst("img.tl-faction-icon") ?: missionCell.selectFirst("img")
            val factionEn = factionImg?.attr("alt")?.trim()
                ?.removeSuffix(" faction")
                ?.takeIf { it.isNotEmpty() }
            val factionIcon = factionImg?.let { absolutize(it.attr("src")) }

            val history = row.selectFirst("td[data-label=\"Loot History\"]")
            val appearances = history?.selectFirst("strong")?.text()
                ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            val lastSeen = history?.select("strong")?.getOrNull(1)?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }

            out += LootEntry(
                missionEn = mission,
                missionZh = mission, // ProtoTrack is English-only; no zh source to map.
                lootEn = loot,
                lootZh = loot,
                imageUrl = imgUrl,
                kind = kind,
                factionIconUrl = factionIcon,
                factionEn = factionEn,
                appearances = appearances,
                lastSeen = lastSeen
            )
        }
        return out
    }

    private fun firstTableWithLootHeaders(doc: Document): Element? = doc.select("table").firstOrNull { t ->
        val h = t.selectFirst("tr")?.select("th,td")?.eachText()
            ?.map { it.trim().lowercase(Locale.ROOT) }.orEmpty()
        h.any { it.startsWith("mission") } && h.any { it.contains("loot") }
    }

    /** Folders used by the site for gear art: weapons, gear, gearsets, brandsets. */
    private fun assetFolder(url: String): String {
        val m = Regex("/assets/division2/([a-z]+)/").find(url)
        return m?.groupValues?.get(1).orEmpty()
    }

    private fun absolutize(src: String): String? {
        val s = src.trim()
        if (s.isEmpty()) return null
        return when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "$BASE$s"
            else -> null // data: URIs and relative-to-page paths: not useful for an ImageView
        }
    }

    private fun isoToday(iso: String): Boolean {
        val parts = iso.split("-")
        if (parts.size != 3) return false
        val y = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        val d = parts[2].toIntOrNull() ?: return false
        val now = Calendar.getInstance()
        return now.get(Calendar.YEAR) == y &&
            now.get(Calendar.MONTH) + 1 == m &&
            now.get(Calendar.DAY_OF_MONTH) == d
    }
}
