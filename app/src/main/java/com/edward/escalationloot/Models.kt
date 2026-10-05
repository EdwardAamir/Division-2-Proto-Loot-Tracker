package com.edward.escalationloot

/** Loot category, derived from the ProtoTrack asset path (e.g. .../weapons/ar.png). */
enum class LootKind(val wire: String) {
    WEAPON("weapons"),
    GEAR("gear"),
    GEARSET("gearsets"),
    BRANDSET("brandsets"),
    OTHER("other");

    companion object {
        fun from(wire: String): LootKind =
            entries.firstOrNull { it.wire == wire.lowercase() } ?: OTHER
    }
}

data class LootEntry(
    val missionEn: String,
    val missionZh: String,
    val lootEn: String,
    val lootZh: String,
    val imageUrl: String? = null,
    /**
     * Additional icon URLs to try when [imageUrl] 404s.
     *
     * The JSON feed gives a display name, not an asset filename, and the site's
     * slugs are not derivable from it in general: "Gear Mods" maps to
     * gear/gearmod.png (singular), while "Hotshot" maps to hotshot.png. So the
     * candidates are generated and tried in order rather than guessed once.
     */
    val imageUrlFallbacks: List<String> = emptyList(),
    val kind: LootKind = LootKind.OTHER,
    /** Faction icon URL, e.g. https://prototrack.gg/assets/division2/factions/rikers.png */
    val factionIconUrl: String? = null,
    /** Human-readable faction name parsed from the faction icon's alt text. */
    val factionEn: String? = null,
    /** How many times this target has appeared in rotation, e.g. 7. Null when absent. */
    val appearances: Int? = null,
    /** Raw "last seen" text from the site, e.g. "31 days ago". */
    val lastSeen: String? = null,
    /**
     * The site flags one target per day as the priority drop. Only present in the
     * JSON feed; null when the data came from the HTML page, which does not
     * expose it.
     */
    val priorityTargetLoot: Boolean = false
)

data class LootDay(
    val date: String,
    val entries: List<LootEntry>,
    /** ISO timestamp from the site's <time datetime="..."> attribute, e.g. 2026-10-04. */
    val dateIso: String? = null,
    /** True when dateIso is today in the device's local timezone. */
    val isToday: Boolean = false
)
