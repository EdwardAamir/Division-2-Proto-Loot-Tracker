# Escalation Loot

Unofficial Android app for **The Division 2** escalation **target loot**, powered by
[prototrack.gg](https://prototrack.gg/target-loot/target-loot.php).

Shows today's five escalation targets with their gear art, and refreshes itself
daily right after the reset.

- **Android 10 → 16** (minSdk 29, targetSdk 35)
- **English / 简体中文** interface
- Refreshes automatically on open and after every daily reset
- Notification when the loot date changes
- Works out the reset time in **your** timezone, not a hardcoded one
- No account, no PC, no VPS — nothing to set up after install

---

## 📥 Download

Grab the APK from the **[Releases page](../../releases)** of this repo.

> **Android will warn you.** Any app installed outside the Play Store is
> "unknown" to the phone, so you will see an "unknown developer" prompt. Tap
> through it. See [Installing](#installing) if it refuses.

| | |
|---|---|
| File | `EscalationLoot.apk` |
| Size | ~12.5 MB |
| Needs | Android 10 or newer |
| Permissions | Internet + notifications. Nothing else. |

---

## Installing

**1. Copy the APK to your phone.** Download it from Releases above, or via USB.

**2. Open it.** Tap the file in your Downloads app.

**3. Allow the install.** Android will say the app is from an unknown source.
Tap **Settings → Allow from this source**, go back, and tap **Install**.

**4. Grant notifications** (optional but recommended) so you get told when the
loot changes.

### If it refuses to install

| Message | What it means |
|---|---|
| "App blocked to protect your device" (Xiaomi/MIUI) | The build was `debuggable`. The APKs here are release-signed, so this should not happen — download it again and make sure you did not rename the file. |
| "App not installed" | An existing copy with a different signature. Uninstall the old one first. |
| "Parsing error" | The file is incomplete. Re-download it and compare the SHA256 published on the Release page. |

### After installing: let notifications through

Xiaomi, Samsung, Huawei and OnePlus phones aggressively kill background apps,
which will stop the daily refresh. If updates stop arriving:

**Settings → Apps → Escalation Loot → Battery → Unrestricted**

or **Settings → Battery → App battery saver → Escalation Loot → No restrictions**

---

## How the countdown works

ProtoTrack resets at **08:00 UTC**, which is **1:00 PM in Pakistan**. That is one
moment in time, so:

- **The countdown number is the same for everyone.** It is a duration — the gap
  between now and that moment — so a user in China sees the same `22h 43m` you do.
- **The clock time next to it is localised.** A user in China sees `4:00 PM CST`
  while you see `1:00 PM PKT`, because China is three hours ahead.

Verified across 13 timezones, including half-hour (`Asia/Kathmandu`, `+5:45`) and
daylight-saving zones.

---

## Data source

Everything comes from [prototrack.gg](https://prototrack.gg/target-loot/target-loot.php).

**No game assets are bundled in this repo or in the APK.** Every mission name,
loot name and gear image is fetched live from ProtoTrack's server when the app
runs. The app is a client of a public web page, nothing more.

Loot data is read from ProtoTrack's JSON feed, which is the same source their
Discord bot uses. The HTML page is only used to add faction icons and loot
history, and only when it reports the same date — so a stale page can never
override current data.

---

## Building from source

Requires **JDK 17** and the **Android SDK** (platform 35). Android Studio works.

```bash
git clone https://github.com/edward_sukuna/escalation-loot.git
cd escalation-loot
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/`

### Signing

The release key is **not** in this repo, on purpose — if it were, anyone could
build an "update" that Android would accept as the real app.

To produce an installable signed APK, create `keystore.properties` in the project
root:

```properties
storeFile=/path/to/your/release.jks
storePassword=yourpassword
keyAlias=youralias
keyPassword=yourpassword
```

Or set `ESCALATION_KEYSTORE`, `ESCALATION_KEYSTORE_PASSWORD`,
`ESCALATION_KEY_ALIAS` and `ESCALATION_KEY_PASSWORD` in the environment.

Without either, the release build still succeeds and produces an **unsigned**
APK. That is intentional so anyone can compile and run the app without secrets.

`keystore.properties` is gitignored. Keep your key backed up somewhere safe and
private: if you lose it, you cannot ship an update to existing installs, because
Android rejects updates signed by a different key.

---

## Troubleshooting

| Symptom | Cause |
|---|---|
| Loot looks like yesterday's | ProtoTrack has not updated yet. Pull to refresh. |
| "ProtoTrack is down" | ProtoTrack itself is failing, not your connection. This has happened before. |
| "No connection · showing saved data" | Your device is offline. The app is showing your last known loot. |
| Notifications stopped | Battery optimiser killed the app. See [After installing](#after-installing-let-notifications-through). |
| Countdown looks wrong | Check Settings → Date & time → Time zone is set automatically. |

---

## Credits

- Loot data and gear images from [prototrack.gg](https://prototrack.gg)
- The Division 2 is a trademark of **Ubisoft Entertainment**
- Built by **edward_sukuna**

This is an unofficial fan project. It is not affiliated with or endorsed by
ProtoTrack or Ubisoft. Please respect ProtoTrack's terms of use and rate limits
if you run your own copy.

## License

[MIT](LICENSE) — see the file for the note on game content.