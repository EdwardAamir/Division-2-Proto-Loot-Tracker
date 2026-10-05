package com.edward.escalationloot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Storage {
    private const val PREF = "loot"
    private const val KEY = "data"

    fun save(c: Context, day: LootDay) {
        c.getSharedPreferences(PREF, 0).edit().putString(KEY, encode(day)).apply()
    }

    fun load(c: Context): LootDay? =
        c.getSharedPreferences(PREF, 0).getString(KEY, null)
            ?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }

    fun clear(c: Context) {
        c.getSharedPreferences(PREF, 0).edit().remove(KEY).apply()
    }

    private fun encode(day: LootDay): String = JSONObject().apply {
        put("date", day.date)
        day.dateIso?.let { put("dateIso", it) }
        put("isToday", day.isToday)
        put("entries", JSONArray().apply {
            day.entries.forEach { e ->
                put(JSONObject().apply {
                    put("me", e.missionEn)
                    put("mz", e.missionZh)
                    put("le", e.lootEn)
                    put("lz", e.lootZh)
                    put("img", e.imageUrl ?: "")
                    // The fallback chain has to survive a cache round-trip, or a
                    // restored day silently loses the icon for any loot whose
                    // first candidate 404s (e.g. "Gear Mods" -> gearmod.png).
                    put("imgFb", JSONArray(e.imageUrlFallbacks))
                    put("kind", e.kind.wire)
                    put("fic", e.factionIconUrl ?: "")
                    put("fEn", e.factionEn ?: "")
                    e.appearances?.let { put("app", it) }
                    put("ls", e.lastSeen ?: "")
                    put("prio", e.priorityTargetLoot)
                })
            }
        })
    }.toString()

    private fun decode(o: JSONObject): LootDay {
        val a = o.getJSONArray("entries")
        val list = (0 until a.length()).map { i ->
            val x = a.getJSONObject(i)
            LootEntry(
                missionEn = x.getString("me"),
                missionZh = x.optString("mz", x.getString("me")),
                lootEn = x.getString("le"),
                lootZh = x.optString("lz", x.getString("le")),
                imageUrl = x.optString("img").takeIf { it.isNotBlank() },
                imageUrlFallbacks = if (x.has("imgFb")) {
                    val fb = x.getJSONArray("imgFb")
                    (0 until fb.length()).mapNotNull { i ->
                        fb.optString(i).takeIf { it.isNotBlank() }
                    }
                } else {
                    emptyList()
                },
                kind = LootKind.from(x.optString("kind", "other")),
                factionIconUrl = x.optString("fic").takeIf { it.isNotBlank() },
                factionEn = x.optString("fEn").takeIf { it.isNotBlank() },
                appearances = if (x.has("app")) x.optInt("app") else null,
                lastSeen = x.optString("ls").takeIf { it.isNotBlank() },
                priorityTargetLoot = x.optBoolean("prio", false)
            )
        }
        return LootDay(
            date = o.getString("date"),
            entries = list,
            dateIso = o.optString("dateIso").takeIf { it.isNotBlank() },
            isToday = o.optBoolean("isToday", false)
        )
    }
}
