/* Escalation Loot (web) — mirrors the behaviour of the Android app.
 *
 * Data is read from ProtoTrack's JSON feed (the same source their Discord bot
 * uses), so what you see here matches the bot. Browsers cannot fetch the feed
 * directly (the server sends no CORS headers), so the load-bearing path is
 * web/data.json — a same-origin daily snapshot this repo commits via GitHub
 * Actions right after the 08:00 UTC reset. Live fetching (direct, then public
 * CORS proxies) runs in parallel as an opportunistic upgrade, and the last
 * successful response is kept in localStorage as a final fallback.
 * Gear icons load straight from prototrack.gg (<img> needs no CORS).
 *
 * Reset instant: 08:00:00 UTC sharp, same as the app (Rollover.kt).
 */
"use strict";

/* ── constants ─────────────────────────────────────── */
const JSON_URL = "https://prototrack.gg/target-loot/target-loot.json";
const BASE = "https://prototrack.gg";
const SNAPSHOT_URL = "./data.json";
const STORAGE_KEY = "escLootWeb:v1";
const LANG_KEY = "escLootWeb:lang";
const RESET_HOUR_UTC = 8;

/* Public CORS proxies are hobby-tier and flaky (some are dead or paywalled at
   any given moment), so they are only an opportunistic upgrade on top of the
   same-origin snapshot — never the load-bearing path. The jsDelivr entry mirrors
   web/data.json from our own repo with CORS open. */
const PROXY_ATTEMPTS = [
  (u) => `https://api.allorigins.win/raw?url=${encodeURIComponent(u)}`,
  (u) => `https://whateverorigin.org/get?url=${encodeURIComponent(u)}`,
  () => "https://cdn.jsdelivr.net/gh/EdwardAamir/Division-2-Proto-Loot-Tracker@main/web/data.json",
];

/* ── i18n (same strings as I18n.kt) ────────────────── */
const T = {
  en: {
    title: "Escalation Loot",
    subtitle: "Division 2 Target Loot",
    langToggle: "中文",
    refresh: "Refresh",
    refreshing: "Refreshing…",
    loading: "Fetching today's target loot…",
    empty: "No target loot yet",
    emptyHint: "Tap Refresh to fetch the latest escalation targets.",
    today: "TODAY",
    updated: "Updated",
    missions: "missions",
    mission: "mission",
    targetLoot: "Target Loot",
    countdown: (d, local, zone) =>
      `Resets in ${d} · ${local}${zone ? " " + zone : ""}`,
    stale: "Showing last known loot — tap Refresh",
    siteDown: "ProtoTrack is down",
    showingSaved: "showing saved data",
    source: "Loot data & gear images from prototrack.gg",
    author: "Made by Discord : edward_sukuna",
    disclaimer:
      "Unofficial fan app. Not affiliated with or endorsed by ProtoTrack or Ubisoft.",
    vendorTitle: "Vendor caches",
    weaponCaches: "Weapon",
    gearCaches: "Gear",
    priority: "Priority",
    live: "live",
    snapshot: "snapshot",
    saved: "saved",
    kind: {
      weapons: "Weapon",
      gear: "Gear",
      gearsets: "Gear Set",
      brandsets: "Brand Set",
      other: "Loot",
    },
  },
  zh: {
    title: "升级目标战利品",
    subtitle: "升级目标战利品速报",
    langToggle: "EN",
    refresh: "刷新",
    refreshing: "正在刷新…",
    loading: "正在获取今日目标战利品…",
    empty: "暂无目标战利品数据",
    emptyHint: "点击右上角刷新按钮获取最新升级目标。",
    today: "今日",
    updated: "更新于",
    missions: "个任务",
    mission: "任务",
    targetLoot: "目标战利品",
    countdown: (d, local, zone) =>
      `距离刷新 ${d} · 当地时间 ${local}${zone ? " " + zone : ""}`,
    stale: "显示上次记录，点击刷新",
    siteDown: "数据源网站暂时故障",
    showingSaved: "显示已保存数据",
    source: "战利品数据与装备图片来自 prototrack.gg",
    author: "由 Discord 制作：edward_sukuna",
    disclaimer: "非官方粉丝应用。与 ProtoTrack 及 Ubisoft 无隶属或合作关系。",
    vendorTitle: "厂商缓存",
    weaponCaches: "武器",
    gearCaches: "装备",
    priority: "优先目标",
    live: "实时",
    snapshot: "快照",
    saved: "已保存",
    kind: {
      weapons: "武器",
      gear: "装备",
      gearsets: "装备套装",
      brandsets: "品牌套装",
      other: "战利品",
    },
  },
};

function storedZh() {
  try { return localStorage.getItem(LANG_KEY) === "zh"; } catch (_e) { return false; }
}

let zh = storedZh();
const i18n = () => T[zh ? "zh" : "en"];

/* ── dom ───────────────────────────────────────────── */
const $ = (id) => document.getElementById(id);
const statusEl = $("status");
const freshnessEl = $("freshness");
const todayBadge = $("todayBadge");
const dateEl = $("dateRow");
const updatedEl = $("updated");
const countdownEl = $("countdown");
const gridEl = $("grid");
const vendorEl = $("vendor");
const vendorTitleEl = $("vendorTitle");
const vendorChipsEl = $("vendorChips");
const emptyEl = $("empty");
const problemEl = $("problem");
const refreshBtn = $("refresh");
const langBtn = $("lang");

/* ── helpers ───────────────────────────────────────── */
const pad2 = (n) => String(n).padStart(2, "0");

function fetchWithTimeout(url, ms) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  return fetch(url, { signal: ctl.signal }).finally(() => clearTimeout(t));
}

/* Mirrors ProtoTrack.kt kindFromCategory — exact case-insensitive match on the
   four category strings the feed actually sends; everything else is "other". */
const KINDS = [
  ["weapon", "weapons"],
  ["gear", "gear"],
  ["gear set", "gearsets"],
  ["brand set", "brandsets"],
];
function kindFromCategory(c) {
  const k = String(c || "").trim().toLowerCase();
  for (const [from, to] of KINDS) if (k === from) return to;
  return "other";
}

/* Mirrors ProtoTrack.kt iconCandidates: folder = category, file = name
   lowercased with non-alphanumerics removed, plus singular/plural variants. */
function iconCandidates(kind, loot) {
  if (kind === "other") return [];
  const slug = loot.toLowerCase().replace(/[^a-z0-9]/g, "");
  if (!slug) return [];
  const dir = `${BASE}/assets/division2/${kind}`;
  const variants = [slug];
  if (slug.endsWith("s") && slug.length > 3) variants.push(slug.slice(0, -1));
  if (!slug.endsWith("s")) variants.push(slug + "s");
  if (slug.startsWith("the")) variants.push(slug.slice(3));
  return [...new Set(variants)].map((s) => `${dir}/${s}.png`);
}

/* Next 08:00 UTC instant, strictly in the future; exactly 24h step (Rollover.kt). */
function nextResetMs(now = Date.now()) {
  const d = new Date(now);
  const t = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(),
    RESET_HOUR_UTC, 0, 0, 0);
  return t <= now ? t + 86400000 : t;
}

function durationText(ms) {
  const total = Math.floor(Math.max(0, ms) / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (zh) {
    return (h > 0 ? `${h}小时` : "") +
      (m > 0 ? `${pad2(m)}分` : "") + `${pad2(s)}秒`;
  }
  if (h > 0) return `${pad2(h)}h ${pad2(m)}m ${pad2(s)}s`;
  if (m > 0) return `${pad2(m)}m ${pad2(s)}s`;
  return `${pad2(s)}s`;
}

function localTimeLabel(target) {
  return new Date(target).toLocaleTimeString([], {
    hour: "numeric", minute: "2-digit", hour12: true,
  });
}

function zoneLabel() {
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    const part = new Intl.DateTimeFormat("en", {
      timeZone: tz, timeZoneName: "short",
    }).formatToParts(new Date()).find((p) => p.type === "timeZoneName");
    if (part && part.value) {
      const v = part.value.trim();
      if (!/^GMT/.test(v) && !/^UTC[+-]/.test(v)) return v;
    }
  } catch (_e) { /* fall through to offset */ }
  const off = -new Date().getTimezoneOffset();
  const sign = off < 0 ? "-" : "+";
  const abs = Math.abs(off);
  const h = Math.floor(abs / 60), m = abs % 60;
  return m ? `UTC${sign}${h}:${pad2(m)}` : `UTC${sign}${h}`;
}

function formatDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || "");
  if (!m) return iso || "";
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  const mo = months[Number(m[2]) - 1];
  // Keep the raw two-digit day ("05"), exactly like ProtoTrack.kt formatDate.
  return mo ? `${m[3]} ${mo} ${m[1]}` : iso;
}

function weekdayOf(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || "");
  if (!m) return "";
  const names = zh
    ? ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]
    : ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  return names[new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getDay()];
}

function isTodayIso(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso || "");
  if (!m) return false;
  const d = new Date();
  return Number(m[1]) === d.getFullYear() &&
    Number(m[2]) === d.getMonth() + 1 &&
    Number(m[3]) === d.getDate();
}

/* ── parsing (mirrors ProtoTrack.kt fetchFromJson) ──── */
function parseDay(text) {
  // Some upstream paths (and a PowerShell-written copy of data.json) carry a
  // UTF-8 BOM; JSON.parse rejects it. Strip it before validating.
  const root = JSON.parse(String(text).replace(/^\uFEFF/, ""));
  if (!Array.isArray(root.missions)) {
    // Proxy wrappers / error pages parse as JSON but carry no missions array;
    // fail loudly so the caller falls through to the next source instead of
    // rendering a garbage empty state.
    throw new Error("feed has no missions array");
  }
  const vendor = root.vendor_caches || {};

  return {
    dateIso: String(root.date || "").trim(),
    updatedAt: String(root.updated_at || "").trim(),
    entries: root.missions
      .filter((m) => m && String(m.name || "").trim() && String(m.loot || "").trim())
      .map((m) => ({
        mission: String(m.name).trim(),
        loot: String(m.loot).trim(),
        short: String(m.short || "").trim(),
        kind: kindFromCategory(String(m.category || "").trim()),
        priority: !!m.priority_target_loot,
      })),
    weaponCaches: Array.isArray(vendor.weapon_caches) ? vendor.weapon_caches : [],
    gearCaches: Array.isArray(vendor.gear_caches) ? vendor.gear_caches : [],
  };
}

/* ── storage ───────────────────────────────────────── */
function saveCache(text, source) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      text, source, ts: Date.now(),
    }));
  } catch (_e) { /* private mode etc. — non-fatal */ }
}

function loadCache() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const c = JSON.parse(raw);
    return c && typeof c.text === "string" ? c : null;
  } catch (_e) { return null; }
}

/* ── fetching ──────────────────────────────────────── */
async function tryText(url, ms) {
  const r = await fetchWithTimeout(url, ms);
  if (!r.ok) throw new Error(`HTTP ${r.status} for ${url}`);
  const text = (await r.text()).replace(/^\uFEFF/, "");
  JSON.parse(text); // cheap syntax gate; parseDay does the real validation
  return text;
}

/* First attempt to fulfil, ignoring all failures (small Promise.any polyfill
   so old Android WebViews that lack Promise.any still work). */
function firstSuccess(promises) {
  return new Promise((resolve, reject) => {
    let pending = promises.length;
    let done = false;
    for (const p of promises) {
      Promise.resolve(p).then(
        (v) => { if (!done) { done = true; resolve(v); } },
        () => { if (--pending === 0 && !done) { done = true; reject(new Error("all live attempts failed")); } }
      );
    }
  });
}

/* All live candidates race in parallel; direct first, CORS proxies and the
   GitHub mirror alongside. Deliberately non-load-bearing: if every one of them
   is down, the snapshot path still shows the loot. */
async function fetchLive() {
  const attempts = [
    tryText(JSON_URL, 5000),
    ...PROXY_ATTEMPTS.map((wrap) => tryText(wrap(JSON_URL), 8000)),
  ];
  return firstSuccess(attempts);
}

async function fetchSnapshot() {
  return tryText(SNAPSHOT_URL, 8000);
}

/* Resolve with the value if it lands within ms, otherwise null. Lets the
   snapshot render instantly and then upgrade when a live source answers,
   without ever blocking on the flaky proxy list. */
function promiseWithin(promise, ms) {
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve(null), ms);
    promise.then(
      (v) => { clearTimeout(t); resolve(v); },
      () => { clearTimeout(t); resolve(null); }
    );
  });
}

/* ── rendering ─────────────────────────────────────── */
const KIND_ACCENT = {
  weapons: "var(--kind-weapon)",
  gear: "var(--kind-gear)",
  gearsets: "var(--kind-gearset)",
  brandsets: "var(--kind-brandset)",
  other: "var(--kind-other)",
};

function setStatus(cls, text) {
  statusEl.className = cls ? `status-text ${cls}` : "status-text";
  statusEl.textContent = text;
}

function setFreshness(label, cls) {
  if (label) {
    freshnessEl.hidden = false;
    freshnessEl.className = `freshness ${cls}`;
    freshnessEl.textContent = label;
  } else {
    freshnessEl.hidden = true;
  }
}

function setProblem(text) {
  if (text) {
    problemEl.hidden = false;
    problemEl.textContent = text;
  } else {
    problemEl.hidden = true;
    problemEl.textContent = "";
  }
}

function renderIcon(slot, candidates) {
  slot.replaceChildren();
  if (!candidates.length) {
    slot.innerHTML = '<span class="icon-fallback">◈</span>';
    return;
  }
  const img = document.createElement("img");
  img.alt = "";
  img.loading = "lazy";
  img.decoding = "async";
  let i = 0;
  img.onerror = () => {
    i += 1;
    if (i < candidates.length) img.src = candidates[i];
    else slot.innerHTML = '<span class="icon-fallback">◈</span>';
  };
  img.src = candidates[0];
  slot.appendChild(img);
}

function renderVendor(day) {
  const chips = [];
  for (const [label, list] of [
    [i18n().weaponCaches, day.weaponCaches],
    [i18n().gearCaches, day.gearCaches],
  ]) {
    if (list.length) {
      if (zh) chips.push(`${label}：${list.join("、")}`);
      else chips.push(`${label}: ${list.join(", ")}`);
    }
  }
  if (chips.length) {
    vendorTitleEl.textContent = i18n().vendorTitle;
    vendorChipsEl.replaceChildren();
    for (const c of chips) {
      const el = document.createElement("span");
      el.className = "vendor-chip";
      el.textContent = c;
      vendorChipsEl.appendChild(el);
    }
    vendorEl.hidden = false;
  } else {
    vendorEl.hidden = true;
  }
}

function renderEmpty(show) {
  emptyEl.hidden = !show;
  if (show) {
    $("emptyTitle").textContent = i18n().empty;
    $("emptyHint").textContent = i18n().emptyHint;
  }
}

function applyDay(day, verdict) {
  setProblem(null);
  renderEmpty(day.entries.length === 0);

  gridEl.replaceChildren();
  day.entries.forEach((e, idx) => {
    const card = document.createElement("article");
    card.className = "card";
    card.style.setProperty("--accent", KIND_ACCENT[e.kind] || KIND_ACCENT.other);
    card.style.animationDelay = `${Math.min(idx * 60, 400)}ms`;

    if (e.priority) {
      const badge = document.createElement("span");
      badge.className = "priority";
      badge.textContent = i18n().priority;
      card.appendChild(badge);
    }

    const slot = document.createElement("div");
    slot.className = "icon-slot";
    renderIcon(slot, iconCandidates(e.kind, e.loot));
    card.appendChild(slot);

    const kind = document.createElement("div");
    kind.className = "kind";
    kind.textContent = i18n().kind[e.kind] || "Loot";
    card.appendChild(kind);

    const mission = document.createElement("div");
    mission.className = "mission";
    mission.textContent = e.mission;
    card.appendChild(mission);

    const loot = document.createElement("div");
    loot.className = "loot-name";
    loot.textContent = e.loot;
    card.appendChild(loot);

    if (e.short) {
      const short = document.createElement("div");
      short.className = "loot-short";
      short.textContent = e.short;
      card.appendChild(short);
    }

    gridEl.appendChild(card);
  });

  todayBadge.hidden = !isTodayIso(day.dateIso);
  todayBadge.textContent = i18n().today;
  dateEl.textContent =
    [weekdayOf(day.dateIso), formatDate(day.dateIso)].filter(Boolean).join("  ");
  const timePart = / (\d{2}:\d{2})$/.exec(day.updatedAt);
  updatedEl.textContent = day.updatedAt
    ? `${i18n().updated} ${timePart ? timePart[1] : day.updatedAt}`
    : "";

  const n = day.entries.length;
  const noun = zh ? `${n} ${i18n().missions}` : `${n} ${n === 1 ? i18n().mission : i18n().missions}`;
  if (verdict === "saved") {
    setStatus("warn", `${noun} — ${i18n().stale}`);
  } else {
    setStatus("ok", noun);
  }
  if (verdict === "live") setFreshness(i18n().live, "live");
  else if (verdict === "snapshot") setFreshness(i18n().snapshot, "snapshot");
  else setFreshness(i18n().saved, "saved");

  renderVendor(day);
}

function renderTotalFailure() {
  setStatus("err", i18n().siteDown);
  setFreshness(null, "");
  setProblem(
    zh
      ? "无法连接数据源。ProtoTrack 网站可能在维护中，请稍后再试。"
      : "Could not reach the data source. ProtoTrack's server may be having trouble — try again in a moment."
  );
}

/* The snapshot can legitimately lag the reset by a few minutes, so when it is
   showing yesterday's loot, say so instead of pretending it is fresh. */
function noteSnapshotAge(day) {
  if (!day.dateIso) return;
  const d = new Date();
  const todayIso =
    `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`;
  if (day.dateIso < todayIso) {
    setStatus("warn",
      zh
        ? `显示最近快照（新战利品在重置后几分钟内更新）· ${day.dateIso}`
        : `Showing the latest snapshot (new loot lands minutes after the 08:00 UTC reset) · ${day.dateIso}`);
  }
}

/* ── countdown ticker ──────────────────────────────── */
let currentTarget = null;
let autoRefreshed = false;

function tick() {
  const now = Date.now();
  const target = nextResetMs(now);
  if (currentTarget !== null && target !== currentTarget) {
    // crossed the reset — refetch the new loot once
    currentTarget = target;
    if (!autoRefreshed) {
      autoRefreshed = true;
      refresh();
      setTimeout(() => { autoRefreshed = false; }, 60000);
    }
  } else {
    currentTarget = target;
  }
  countdownEl.textContent =
    i18n().countdown(durationText(target - now), localTimeLabel(target), zoneLabel());
}

/* ── refresh flow ──────────────────────────────────── */
let loading = false;

function setRefreshSpinner(on) {
  refreshBtn.disabled = on;
  refreshBtn.textContent = on ? i18n().refreshing : i18n().refresh;
}

async function refresh() {
  if (loading) return;
  loading = true;
  setRefreshSpinner(true);
  if (!gridEl.childNodes.length) setStatus("", i18n().loading);
  setProblem(null);

  // Start the live race now; the snapshot is the fast same-origin path.
  const livePromise = fetchLive().catch(() => null);

  try {
    const snapText = await fetchSnapshot();
    const snapDay = parseDay(snapText);
    applyDay(snapDay, "snapshot");
    noteSnapshotAge(snapDay);
    setRefreshSpinner(false); // content on screen — refresh is already done
  } catch (_eSnap) {
    // No snapshot (first run, CDN miss, local file://). Straight to live.
    try {
      const liveText = await livePromise;
      saveCache(liveText, "live");
      applyDay(parseDay(liveText), "live");
      setRefreshSpinner(false);
    } catch (_eLive) {
      const cached = loadCache();
      if (cached) {
        try {
          applyDay(parseDay(cached.text), "saved");
        } catch (_eCached) {
          renderTotalFailure();
        }
      } else {
        renderTotalFailure();
      }
      setRefreshSpinner(false);
    }
  }

  // Opportunistic upgrade over whatever the snapshot shows, but never block on
  // the flaky proxy list: give them a few seconds, then move on.
  const liveText = await promiseWithin(livePromise, 5000);
  if (liveText) {
    try {
      saveCache(liveText, "live");
      applyDay(parseDay(liveText), "live");
    } catch (_eUpgrade) { /* snapshot render stays */ }
  }

  loading = false;
}

/* ── language ──────────────────────────────────────── */
function applyLang() {
  langBtn.textContent = i18n().langToggle;
  refreshBtn.textContent = loading ? i18n().refreshing : i18n().refresh;
  $("title").textContent = i18n().title;
  $("subtitle").textContent = i18n().subtitle;
  $("footerAuthor").textContent = i18n().author;
  $("footerCredit").textContent = i18n().source;
  $("footerDisclaimer").textContent = i18n().disclaimer;
  document.documentElement.lang = zh ? "zh" : "en";
  tick(); // recount in the new language
}

function toggleLang() {
  zh = !zh;
  try { localStorage.setItem(LANG_KEY, zh ? "zh" : "en"); } catch (_e) { /* non-fatal */ }
  applyLang();
}

/* ── boot ──────────────────────────────────────────── */
function boot() {
  refreshBtn.addEventListener("click", refresh);
  langBtn.addEventListener("click", toggleLang);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) tick();
  });

  applyLang();

  // Show what we have instantly, then fetch the freshest copy.
  const cached = loadCache();
  if (cached) {
    try {
      applyDay(parseDay(cached.text), "saved");
    } catch (_e) {
      setStatus("", i18n().loading);
    }
  } else {
    setStatus("", i18n().loading);
  }

  refresh(); // auto-refresh on open, like the app
  setInterval(tick, 1000);
}

/* Pure functions are exported for the node test harness; the browser path
   never sees `module`, so it falls through to boot(). */
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    parseDay,
    iconCandidates,
    nextResetMs,
    durationText,
    formatDate,
    weekdayOf,
    isTodayIso,
    kindFromCategory,
  };
} else {
  boot();
}