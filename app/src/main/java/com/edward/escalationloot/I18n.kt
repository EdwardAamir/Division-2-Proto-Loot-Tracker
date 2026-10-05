package com.edward.escalationloot

/**
 * Client-side translation. ProtoTrack serves English only (no locale links and
 * no CJK characters on the page), so Chinese labels here are ours, not the
 * site's. Mission and loot names stay as the site provides them.
 */
class I18n(val zh: Boolean) {
    val appTitle: String get() = t("Escalation Loot", "升级目标战利品")
    val subtitle: String get() = t("Escalation Target Loot", "升级目标战利品速报")
    val refresh: String get() = t("Refresh", "刷新")
    val refreshing: String get() = t("Refreshing…", "正在刷新…")
    val loading: String get() = t("Fetching today's target loot…", "正在获取今日目标战利品…")
    val empty: String get() = t("No target loot yet", "暂无目标战利品数据")
    val emptyHint: String get() = t(
        "Pull down the refresh button to fetch the latest escalation targets.",
        "点击右上角刷新按钮获取最新升级目标。"
    )
    val today: String get() = t("TODAY", "今日")
    val updated: String get() = t("Updated", "更新于")
    val sources: String get() = t("Made by Discord: edward_sukuna", "由 Discord 制作：edward_sukuna")

    /**
     * Attribution for the data, shown under the author credit.
     *
     * The app ships none of ProtoTrack's data: every mission name, loot name
     * and gear image is fetched live from their server at runtime and is not
     * redistributed with the APK. Crediting the source is both the honest thing
     * to do and the practical protection -- anyone asking where the numbers come
     * gets pointed at the right place instead of at us.
     *
     * Kept separate from [sources] so the author credit stays where it was and
     * the two are not confused for each other.
     */
    val dataCredit: String get() = t(
        "Loot data & gear images from prototrack.gg",
        "战利品数据与装备图片来自 prototrack.gg"
    )

    /**
     * Unaffiliated notice. ProtoTrack did not author or endorse this app, and
     * saying so plainly is better than leaving it ambiguous.
     *
     * Ubisoft is named as well because the gear images the app displays are
     * Division 2 artwork. ProtoTrack hosts them, but the underlying IP is
     * Ubisoft's, so naming only the host site would leave that layer uncovered.
     */
    val notAffiliated: String get() = t(
        "Unofficial fan app. Not affiliated with or endorsed by ProtoTrack or Ubisoft.",
        "非官方粉丝应用。与 ProtoTrack 及 Ubisoft 无隶属或合作关系。"
    )
    val missions: String get() = t("missions", "个任务")
    val mission: String get() = t("mission", "任务")
    val targetLoot: String get() = t("Target Loot", "目标战利品")
    val appearances: String get() = t("Appearances", "出现次数")
    val lastSeen: String get() = t("Last seen", "上次出现")
    val neverSeen: String get() = t("never", "无记录")
    val updateFailed: String get() = t("Update failed", "更新失败")
    val stale: String get() = t("Showing last known loot — tap Refresh", "显示上次记录，点击刷新")

    /** ProtoTrack itself is broken or unreachable. Not a user-side problem. */
    val siteDown: String get() = t("ProtoTrack is down", "数据源网站暂时故障")
    val noConnection: String get() = t("No connection", "无法连接网络")
    val showingCached: String get() = t("showing saved data", "显示已保存数据")
    /**
     * Countdown line shown under the date, e.g.
     *   EN:  "Resets in 35m 24s · 1:10 PM PKT"
     *   ZH:  "距离刷新 35分24秒 · 当地时间 13:10"
     *
     * [localTime] and [zone] come from the device's own timezone, so a user in
     * China sees 4:10 PM and one in the US sees 8:10 AM or 9:10 PM — all the
     * same underlying instant, expressed in their own clock.
     */
    fun countdownText(remaining: Long, localTime: String, zone: String): String {
        val total = (remaining / 1000).toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val duration = if (zh) {
            // Zero-padded so the label does not jitter in width as seconds roll 9 -> 10.
            buildString {
                if (h > 0) append(String.format(java.util.Locale.US, "%d小时", h))
                if (m > 0) append(String.format(java.util.Locale.US, "%02d分", m))
                append(String.format(java.util.Locale.US, "%02d秒", s))
            }
        } else {
            when {
                h > 0 -> String.format(java.util.Locale.US, "%02dh %02dm %02ds", h, m, s)
                m > 0 -> String.format(java.util.Locale.US, "%02dm %02ds", m, s)
                else -> String.format(java.util.Locale.US, "%02ds", s)
            }
        }
        val zoneSuffix = zone.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return if (zh) {
            "距离刷新 $duration · 当地时间 $localTime$zoneSuffix"
        } else {
            "Resets in $duration · $localTime$zoneSuffix"
        }
    }
    val newLootTitle: String get() = t("New target loot", "新的目标战利品")
    val newLootBody: String get() = t("Escalation targets updated for %s", "%s 的升级目标战利品已更新")
    val channelName: String get() = t("Escalation Loot", "升级目标战利品")
    val langToggle: String get() = if (zh) "EN" else "中文"

    fun kind(kind: LootKind): String = when (kind) {
        LootKind.WEAPON -> t("Weapon", "武器")
        LootKind.GEAR -> t("Gear", "装备")
        LootKind.GEARSET -> t("Gear Set", "装备套装")
        LootKind.BRANDSET -> t("Brand Set", "品牌套装")
        LootKind.OTHER -> t("Loot", "战利品")
    }

    /** Every escalation day is a weekday, so name them for the reader. */
    fun weekdayOf(dateIso: String?): String? {
        val y = dateIso?.take(4)?.toIntOrNull() ?: return null
        val m = dateIso?.take(7)?.drop(4)?.toIntOrNull() ?: return null
        val d = dateIso?.take(10)?.drop(8)?.toIntOrNull() ?: return null
        if (dateIso.length < 10) return null
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(y, m - 1, d)
        val names = if (zh) arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        else arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return names[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
    }

    private fun t(en: String, cn: String) = if (zh) cn else en
}
