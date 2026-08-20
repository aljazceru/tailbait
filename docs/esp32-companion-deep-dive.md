# Deep Dive: ESP32 Companion Device for TailBait

**Status:** exploration / design study — nothing here is implemented yet.
**Goal:** figure out what an ESP32-class board physically tethered to (or carried with) the phone could add to TailBait, primarily WiFi monitoring, possibly BLE as well — and what it would take to integrate.

---

## 1. TL;DR

An ESP32 companion is technically very feasible and adds real capability, but **not via the vector most people first imagine** (tracking a modern phone over WiFi probes — those MACs are heavily randomized, same problem we already fight on BLE). The genuinely valuable additions are:

1. **Unthrottled, always-on scanning.** The ESP32 can BLE-scan and WiFi-sniff continuously and stream to the phone. This sidesteps Android's background scan restrictions entirely — one of TailBait's biggest structural weaknesses today.
2. **Portable hotspot / router following you.** A MiFi or phone-hotspot that follows you is visible via its (much more stable) beacon SSID/BSSID. There is currently *no* tooling in TailBait for this class of tail.
3. **Non-randomized WiFi clients.** Laptops, IoT junk, older phones broadcast probe requests with static MACs and often their saved-SSID list — strong, stable identifiers BLE never sees.
4. **AP context / indoor location fingerprinting.** Visible-AP sets give location context where GPS is weak (urban canyons, indoors, malls) and improve location dedup quality.
5. **Cross-radio identity correlation.** Same physical device visible on both WiFi and BLE with correlated RSSI/timing → identity evidence that survives independent MAC rotation per radio. Research-grade, opt-in, later phase.

The right transport is a **BLE GATT link** (phone = central, ESP32 = peripheral), because it keeps the ESP32's WiFi radio free to promiscuously channel-hop. Throughput needs are trivially met (~250 B/s average vs ~5+ KB/s practical GATT capacity). The Android integration maps cleanly onto the existing pipeline: the whole fingerprint → `DeviceLocationRecord` → multi-location detection flow is already radio-agnostic in structure, so WiFi devices can flow through it with a schema extension (migration v11 → v12), and phone GPS tagging is already there (`LocationTracker` / `UserPath` breadcrumbs).

Estimated effort: **firmware MVP ≈ a few weekends**, **app ingest + UI ≈ 1–2 weeks part-time**, correlation features later.

---

## 2. What problem are we solving

TailBait today = phone-only BLE scanner:

- Phone BLE scans are bursty (AlarmManager-driven, ~60s scans every 5 min by default) because Android throttles and kills background scanning. Coverage gaps are the norm.
- The phone sees exactly one radio (BLE). A tail's phone, laptop, or hotspot radiates on WiFi too — invisible to us.
- Location comes from the phone's GPS, which is decent outdoors but poor indoors/urban.

The companion concept: **a dedicated, always-on RF sensor that the phone orchestrates and geotags.** Phone contributes GPS, UI, storage, analysis; ESP32 contributes raw RF visibility the OS won't give us.

Two deployment modes fall out of the same hardware:

| Mode | Description | What it's for |
|---|---|---|
| **Carry-along** | ESP32 in pocket/bag, live-streams to phone over BLE | Continuous coverage while moving; replaces/augments phone scans |
| **Fixed sentinel** | ESP32 left at home/office/hotel, wall-powered, buffers locally, syncs when phone returns | Detects the tail visiting *your* locations while you're away; anchors location context |

Both use the same firmware and ingest path. The sentinel mode is quietly the most interesting: today the detection model only knows about locations *the phone visited*. A sentinel extends coverage to locations you care about but aren't at — "device that follows me also pings near my home at 3am while I'm across town" is a very strong signal that currently cannot exist in this app.

---

## 3. ESP32 radio reality check

### 3.1 What promiscuous WiFi mode gives you

ESP-IDF's promiscuous mode (`esp_wifi_set_promiscuous()` with `WIFI_PROMIS_FILTER_MASK_MGMT`) delivers full 802.11 management frames with per-frame RSSI + channel in `rx_ctrl`. Relevant frame subtypes:

- **Probe requests (0x04)** — clients hunting for known networks. Contains SA (source MAC) and, on many stacks, Information Elements including the **requested-SSID list**. This is the "WiFi probe" tail-detection vector.
- **Beacons (0x08)** — APs announcing themselves. BSSID, SSID, channel, RSSI. Stationary infrastructure mostly (great location fingerprint, not a tail itself) *except* portable hotspots, which are tails with a name.
- **Probe responses (0x05)** — like beacons, sent to a specific requester.
- **Data frames (headers only)** — an *associated* client transmits data frames whose MAC is typically stable for the duration of the association even when probe MACs rotate. No payload (encrypted), header MAC + RSSI still useful. Phase-2 material.

One radio, one channel at a time: the ESP32 must **channel-hop** (typical dwell 100–300 ms across 1–13; prioritize 1/6/11). A client's probe burst lasts a few frames on one channel, so hop cadence determines catch probability per device. Empirically, projects like ESP32-Marauder/WiFi-Pentesting-Tool frameworks and wardriver builds catch plenty of clients with ~150 ms dwell.

### 3.2 BLE from the ESP32

NimBLE (recommended over Bluedroid: ~50 KB less RAM) gives a passive scanner delivering **raw advertisement payloads** (MAC, RSSI, adv data bytes). This is exactly the input `BleScannerManager.handleScanResult()` consumes today — meaning the ESP32 can forward raw ADV bytes and the app runs its *existing* parser stack (`ManufacturerDataParser`, `TrackerAnalyzerFactory`, `BeaconDetectionUtils`, fingerprinting) **unchanged**. Keep firmware dumb; keep intelligence on the phone.

### 3.3 The coexistence problem (the one real constraint)

WiFi and BT share one RF path on all ESP32 variants — they time-slice. Consequences:

- **WiFi promiscuous + BLE scanning simultaneously**: possible with software coexistence (`CONFIG_SW_COEXIST_ENABLE`), at reduced frame-catch rate on both. Workable but must be tuned; expect to trade dwell times.
- **WiFi STA (connected) + promiscuous on another channel**: not possible. Connected mode locks the channel. This kills "stream over WiFi to phone hotspot while sniffing all channels" as a clean design.
- **WiFi promiscuous + BLE GATT server**: this is the sweet spot. The GATT connection is low duty-cycle (connection events every 30–50 ms moving a few hundred bytes), so the coexistence hit is modest, and WiFi stays free to hop all channels.

**Conclusion that falls out:** backhaul over BLE GATT, WiFi free for sniffing, BLE scanning on the ESP32 either time-sliced in windows (e.g., 10 s WiFi / 10 s BLE) or run continuously with coexistence and accepted loss. All of this is firmware-tunable at runtime via config characteristic.

### 3.4 Hardware pick

| Board | Radios | Notes |
|---|---|---|
| **ESP32-S3** (recommended) | WiFi b/g/n + BLE 5 | Plenty of RAM (+PSRAM options), native USB for dev/flashing, good module availability with u.FL for external antenna |
| ESP32-C6 | WiFi 6 + BLE 5.3 + 802.15.4 | Future bonus: Thread/Zigbee sniffing (Tile uses 802.15.4-ish? no — but many trackers/IoT do) |
| ESP32-C3 | WiFi + BLE 5 | Cheapest/smallest, fine for MVP, less RAM headroom |
| classic ESP32 | WiFi + BT classic + BLE | Works (Marauder runs on it), but no reason vs S3 today |

External antenna (u.FL/IPEX modules) meaningfully extends sniff range; onboard PCB antennas are okay. Expect ~50–150 m outdoor WiFi beacon reception with a decent antenna.

**Power:** continuous WiFi promiscuous + BLE ≈ 80–120 mA → 2000 mAh LiPo ≈ ~1 day carry-along. BLE-only ≈ 40–60 mA. Sentinel mode on USB = infinite, with burst/sleep profiles stretching battery mode to days/weeks.

### 3.5 No-GPS-on-sensor is fine (and better)

The ESP32 doesn't need its own GPS. Records are streamed live and geotagged by the phone at ingest using the existing `LocationTracker`/`LocationRepository` path. For offline-buffered records (sentinel mode, link drops), the app already records `UserPath` breadcrumbs — historical geotagging = nearest-in-time user path point (linear interpolation between the two straddling points if we want to be fancy). A cheap RTC (DS3231) or just phone-resync-on-connect handles device-side timestamps: phone writes epoch at connect, firmware stores offset against `esp_timer_get_time()`.

---

## 4. The randomization reality (what WiFi identifiers are actually worth)

The value of WiFi sniffing depends almost entirely on what the transmitting OS does to its MAC. Current state of the world (verify empirically per release — this shifts):

| Source | Probe MAC | SSID list in probe? | Verdict for detection |
|---|---|---|---|
| Modern iPhone (iOS 8+, current) | Randomized, rotates frequently | No (directed/empty probes) | Nearly useless as stable ID |
| Modern Android (10+) | Randomized, rotates (per burst/day by version) | No | Nearly useless as stable ID |
| Android 6–9 era | Per-SSID hash or real | Sometimes partial | Marginal |
| **Windows laptops** | Often **real MAC** (randomization off by default for many users) | **Yes — saved SSIDs broadcast** | **Gold: stable MAC + strong fingerprint** |
| **macOS laptops** | Randomized on recent versions; real MAC historically | Older: yes | Mixed; older = gold |
| **IoT / cheap gadgets / cars** | Real static MAC | Often full SSID list | **Gold** |
| **Portable hotspot (MiFi/phone hotspot)** | BSSID may randomize per enable cycle (Android 10+), but **SSID is stable** | n/a (it's an AP) | **Gold via SSID-over-locations** |
| Station WiFi clients (data frames) | Stable *while associated* | n/a | Good secondary signal |

Three takeaways:

1. **"Phone tailing you via WiFi probes" is mostly dead on modern phones** — same arms race as BLE. Anyone claiming easy WiFi phone-tracking is describing 2015.
2. **The WiFi targets that matter are laptops, gadgets, and hotspots** — exactly the devices a dedicated tail (rather than a lazy one) tends to have. A laptop in the tail's bag is a beacon of real MAC + home SSID.
3. **Hotspot-following detection has no randomization defense at all**: the SSID is the identity, and it's user-chosen text. "Same SSID visible at your office, gym, and home" is a hard signal, and TailBait currently has *zero* visibility into that class.

Cross-radio note: a tail's modern phone still leaks one thing — *simultaneity*. Its WiFi and BLE MACs are randomized independently, but both radios' presence/RSSI tracks your movement in correlated fashion. Cross-radio temporal+RSSI correlation can merge identities neither radio alone sustains. This is the "advanced heuristic," Phase 3, needs careful false-positive control (everyone on your bus correlates).

---

## 5. Transport & pairing decision

Options considered for phone ↔ ESP32:

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **BLE GATT (phone central)** | Keeps WiFi free to hop channels; low power; no network config; works in background; app already has all permissions (`BLUETOOTH_CONNECT`); Nordic client lib is a drop-in alongside existing scanner dep | Throughput ceiling (~5–10 KB/s effective) — irrelevant, we need ~0.25 KB/s; Android may throttle background BLE | ✅ **Recommended** |
| WiFi backhaul (ESP32→phone hotspot or ESP32 AP) | High throughput; standard sockets | Channel lock kills promiscuous hopping (or bursty connect/dump/hop dance); phone hotspot battery drain; per-place network config | ❌ only as debug mode |
| USB serial (OTG) | Trivial protocol, powers the board | Cable + OTG ruins mobility; charging conflicts; USB host API boilerplate | ❌ for carry mode; ✅ for bench dev & firmware flashing |
| BT classic SPP | Simple | Dead-ish on Android; not on S3/C3; conflicts with BLE role | ❌ |

**Throughput budget (BLE GATT):** busy street — say 150 unique WiFi devices/min (probes+beacons after on-firmware dedup) + 50 BLE devices/min, ~50 B/record average (probe with truncated 32 B SSID) ≈ **~200 B/s**, spiking to a few KB/s during sync of a sentinel's buffered backlog. GATT notifications with a 247-byte MTU and 30 ms connection interval comfortably deliver 5+ KB/s. Not a constraint, even with a generous safety margin. (If backlog sync ever becomes huge: bump connection interval briefly during flush, or just let it trickle — it's background data.)

**Pairing/bonding model:** LE Secure Connections, bond stored both sides. ESP32 has no display → practical options: (a) *Just Works* + proximity check (app rejects pairing unless ESP32 RSSI > threshold, plus physical button press on device to open a 30 s pairing window — AirPods-style), or (b) passkey entry (app shows 6 digits, single button on ESP32 used morse-style is awful — skip). Recommendation: (a) for v1, threat model being "attacker needs physical proximity during a 30 s window while you're holding the device."

---

## 6. Architecture

### 6.1 Firmware block design (ESP-IDF + NimBLE)

```
┌────────────────────────────── ESP32-S3 ──────────────────────────────┐
│                                                                      │
│  WiFi promiscuous task          BLE scan task (NimBLE)               │
│  - channel hopper (dwell cfg)   - passive scan, raw adv events       │
│  - frame classifier:            - (time-sliced windows w/ WiFi,      │
│    probe-req / beacon /           or coexist mode, runtime cfg)      │
│    data-hdr                      - on-device dedup: report per-MAC   │
│  - per-MAC rate limiting          every N s or on payload change     │
│    (dedup state in RAM)                                            │
│         │                                │                          │
│         └────────────┬───────────────────┘                          │
│                      ▼                                               │
│            record ring buffer (RAM, opt. LittleFS spill)             │
│              [type][mac][rssi][ch][t_dev][len][payload…]             │
│                      │                                               │
│  GATT server (NimBLE) ◄── config/write: time sync, scan config,     │
│  - SVC: TailBait Comms     mode flags, flush, status poll             │
│  - CHR data  (notify) ◄─── stream records                             │
│  - CHR ctrl   (write)                                                 │
│  - CHR status (notify): battery V, heap, ch, fw version               │
│                                                                      │
│  housekeeping: watchdog, LED status, button (pairing), battery ADC   │
└──────────────────────────────────────────────────────────────────────┘
```

Firmware v0 can literally be the ESP-IDF promiscuous example + NimBLE GATT server example glued together. Prior art to crib from (code-level): ESP32-Marauder (promiscuous + parsing), ESP32-Wardriver projects, NimBLE-Arduino scanner+server examples. Custom firmware, not ESPHome (ESPHome is STA-mode-centric, wrong shape).

### 6.2 Wire protocol sketch (v1, deliberately boring)

- Frame on the data characteristic: `u8 msgType | u8 flags | u8 len | payload[len]`, batched several per notification, little-endian.
- Record payload (packed struct) for WiFi probe: `mac[6] rssi(i8) ch(u8) t_dev(u32, ms since boot) ssidLen(u8) ssid[≤32] ieFlags(u8 bitfield: hasSSIDlist, isDirected …)`.
- WiFi beacon: same, SSID from beacon (or SSID hash to save bytes + a "known?" flag), plus a `u16 beaconInterval`.
- BLE adv: `mac[6] rssi(i8) advType(u8) t_dev(u32) advLen(u8) rawAdv[≤62]` — full raw payload so phone-side parsers run unmodified.
- Control writes from phone: `SET_TIME(epoch_ms)`, `SET_CONFIG{mode, dwellMs, hopPlan, bleEnabled, bleWindow}`, `FLUSH`, `GET_STATUS`.
- Status notify: `fwVer, battMv, heapFree, curCh, dropCount`.
- Timestamps: all records carry device-uptime; phone converts via the time-sync offset captured at connect (and re-synced periodically; log drift for sentinel backlogs). v2 if needed: ping/pong characteristic for latency estimation.

JSON-lines was considered for v0-over-serial debugging — keep binary+CBOR-ish framing for GATT.

### 6.3 Android integration — mapped onto the actual codebase

New `com.tailbait.companion` package; the design principle is **the ingest path mimics `BleScannerManager.processScanResultsInternal()`** so all downstream machinery (fingerprint linking, shadow keys, detection, alerts, map) works untouched.

```
CompanionLinkManager        (new) — Nordic BLE client: connect/auto-reconnect by bonded
                                    MAC or advertised service UUID, MTU negotiation,
                                    notification collector → Flow<CompanionRecord>
CompanionDeviceRepository   (new) — paired-device store (DataStore): MAC, name, mode
                                    (carry/sentinel), sentinel location, fw version
CompanionIngestor           (new) — maps CompanionRecord → DeviceRepository /
                                    LocationRepository calls; live GPS tagging or
                                    UserPath nearest-in-time backfill; per-source
                                    RSSI normalization metadata
```

Wiring points in existing code:

- `TailBaitService.startTracking()` — start `CompanionLinkManager` alongside `locationTracker`; collect its flow into ingest. Service already holds `FOREGROUND_SERVICE_TYPE_LOCATION` + WakeLock machinery; a long-lived BLE connection from a foreground service is fine.
- `di/` — new `CompanionModule` providing the link manager; note `libs.versions.toml` already pins `nordic-ble-core`/`nordic-ble-scanner` 1.3.1 → add `no.nordicsemi.android.kotlin.ble:client:1.3.1` (same line). The unused `nordic-ble-ktx = 2.7.0` var can be dropped or used for the legacy Java client if the Kotlin client API is missing pieces.
- **DB migration v11 → v12** (schemas dir currently at 11):
  - `ScannedDevice`: add `radio TEXT` (`BLE` | `WIFI_PROBE` | `WIFI_AP` | `WIFI_DATA`), `ssid TEXT?`, `channel INT?`, `source_id TEXT?` (observer: `PHONE` or companion device id). Consider composite uniqueness `(address, radio, source_id)`.
  - `DeviceLocationRecord`: add `source TEXT` (`PHONE_BLE`, `ESP32_WIFI`, `ESP32_BLE`, …) and `observer_id TEXT?` — needed because two observers report different RSSI scales and slightly different positions.
  - New table `companion_devices` (paired device registry + last sync, battery, fw).
  - WiFi "fingerprint": for probe SSID-lists, build fingerprint analog of `payloadFingerprint` (e.g., `WIFI:hash(sorted ssids)` or `WIFI:oui+ie-layout`), feeding the *existing* `upsertDeviceWithFingerprint` correlation machinery — hotspot SSID is literally a stable fingerprint string.
- `DetectionAlgorithm` — runs as-is over WiFi devices (multi-location + distance thresholds don't care about radio). New alert flavors:
  - `HOTSPOT_FOLLOW`: same SSID at N distant locations (needs small analyzer + whitelist of your own hotspots).
  - `CROSS_RADIO_MERGE` (Phase 3): temporal/RSSI correlation across `radio` groups → link WiFi device row to BLE device row via existing `linkedDeviceId` machinery.
- `ThreatScoreCalculator` — weight tweaks: probe-SSID-list match = strong stable-identifier evidence (like tracker-type boost today); observer-source RSSI reliability factor.
- UI: Settings → "Companion device" screen (pair flow, status card: connection, battery, current channel, records/s; mode switch carry/sentinel; sentinel location pin). DeviceList/Detail: radio badge + SSID display. Map: layer toggle. LearnMode: works unchanged (it's a manual scan trigger — can include companion-triggered "FLUSH").
- Permissions: **no new ones.** `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` already granted; phone does zero WiFi scanning (that's the point), GPS tagging reuses location permission. Neat side-effect: WiFi-derived context without Android's WiFi-scan-requires-location-GPS-toggle dance.

### 6.4 Data flow (carry-along mode, steady state)

```
ESP32 hops channels, classifies frames, dedups → ring buffer
  → GATT notify (~1-10/s bursts) → CompanionLinkManager → Flow
  → CompanionIngestor:
      1. time-convert via sync offset
      2. geotag: fresh GPS (or nearest UserPath point for backfill)
      3. LocationRepository.findOrCreateLocation(lat, lon, 50 m)   [existing]
      4. DeviceRepository.upsertDeviceWithFingerprint(...)         [existing]
      5. DeviceLocationRecord(source=ESP32_WIFI, observer=…)       [new cols]
  → DetectionWorker / TailBaitService cycle picks it up on next run
```

Optional synergy: when companion is connected and streaming BLE, the phone can *reduce its own scan cadence* (setting: "prefer companion scanner") — the ESP32 is unthrottled, so background coverage improves while phone battery drops. Big honest win.

---

## 7. Detection value analysis (what alerts become possible)

| Signal | Possible today? | With companion | Strength |
|---|---|---|---|
| BLE device at N locations | ✅ | ✅ + continuous coverage, fewer gaps | Existing |
| MAC-rotating BLE device (fingerprint/shadow) | ✅ best-effort | ✅ better (24/7 observation catches rotation edges) | Existing+ |
| **Hotspot/MiFi following user (SSID)** | ❌ | ✅ | **High — stable ID** |
| **Laptop/IoT probe MAC following user** | ❌ | ✅ | **High when present — stable MAC** |
| Probe SSID-list as super-fingerprint | ❌ | ✅ (hash list → existing fingerprint machinery) | High |
| Device seen near sentinel while user is away | ❌ (impossible by construction) | ✅ | **Novel & high** |
| AP-set similarity → location confidence | ❌ | ✅ auxiliary | Medium (improves GPS dedup indoors) |
| Cross-radio identity merge | ❌ | ⚠️ Phase 3 | Medium, FP-prone |
| WiFi data-frame MAC stability during association | ❌ | ⚠️ Phase 2+ | Medium |

False-positive considerations: neighbors' static devices near sentinels (mitigate: sentinel locations get a "home baseline learn" like LearnMode); city-wide common SSIDs ("eduroam", "iPhone") must be whitelisted/ignored for hotspot-follow (it's *BSSID+SSID* or SSID-rarity weighted, not raw SSID alone); probe SSID lists with common entries need rarity scoring.

---

## 8. Prior art

- **WiGLE** — the classic wardriving app (WiFi+BLE+GPS logging); closest existing analog for the *data*, but phone-scanner-based (throttled), different mission.
- **ESP32 Marauder** — proof that promiscuous sniffing + BLE + UI runs on ESP32-class hardware; good code reference for frame parsing (it's an offense tool; we take parsing only).
- **Pwnagotchi** — WiFi recon on Pi Zero W; bettercap integration; demonstrates the "companion sensor with personality" form factor.
- **Android's Unknown Tracker Alerts / Apple's tracker alerts** — platform-level BLE-only coverage; leaves WiFi class entirely unaddressed (our niche).
- ESP-IDF `promiscuous` examples, NimBLE multi-role examples — the literal building blocks.

---

## 9. Legal & ethical notes (not legal advice)

- Passive reception of unlicensed-spectrum signals (management frames, beacon SSIDs) is generally lawful; we never decrypt or store payload *content*. Probes/beacons are broadcast by design.
- Probe SSID lists and beacon SSIDs can contain personal data (people's home network names, sometimes names). Consistent with TailBait's stance: on-device only, no cloud, user-controllable retention, and consider hashing SSIDs for *storage* while keeping plaintext only in UI/passing (sentinel/home SSIDs especially).
- Framing matters: this is defensive (detect being followed), not a tool for tracking others. Keep UX language and README aligned with that, same as the existing app.
- Some jurisdictions have opinions on RF monitoring in general; ship a "check your local laws" note in Help screen like responsible wardriving tools do.

---

## 10. Risks & open questions

1. **Coexistence tuning** (WiFi promiscuous + NimBLE multi-role) is the main technical risk — needs a bench spike early. Fallback: strict time-slice windows (works, just reduces catch rates).
2. **Frame catch-rate realism**: quantify probe catch probability vs hop dwell in real environments before promising detection quality. (Spike: log raw capture rates for a day on a desk facing a street.)
3. **Android background BLE connection longevity** across OEMs (Xiaomi et al.): mitigate via foreground service (already present) + auto-reconnect + "companion lost" notification.
4. **RSSI semantics differ per observer** (phone vs ESP32, antenna, orientation): store `observer_id` per record now (cheap) even if normalization comes later.
5. **Hotspot BSSID randomization cadence** per vendor (Android 10+ re-randomizes SoftAP on enable): rely on SSID as primary key; verify on real devices.
6. **Buffer integrity for sentinel mode** (power loss): LittleFS append-log with checksummed records.
7. **How much do we care about 5 GHz?** ESP32 (2.4 GHz only) misses 5 GHz-heavy client traffic; laptops/hotspots still beacon 2.4 by default. An ESP32-C6 (WiFi 6, still 2.4) doesn't fix this; 5 GHz sniffing would need different hardware (out of scope for v1; note it).

---

## 11. Phased roadmap

**Phase 0 — bench spike (1–2 evenings)**
- ESP32 devkit on USB: promiscuous sniffer → serial JSON lines (probes+beacons, RSSI, channel). Measure catch rates and frame volume in your environment. Convinces everyone with data.

**Phase 1 — firmware MVP (a few weekends)**
- NimBLE GATT server + ring buffer + time sync + binary framing + WiFi-only sniffing; phone-side debug screen that just displays the stream (or nRF Connect for Android for free during dev).

**Phase 2 — app integration (1–2 weeks part-time)**
- `CompanionModule`/`CompanionLinkManager`/`CompanionIngestor`, DB v12 (`radio`, `source`, `ssid`, `observer_id`, `companion_devices`), pairing UX, device list/map badges, `HOTSPOT_FOLLOW` + stable-probe-MAC detection through existing pipeline, export columns.
- Companion BLE scan forwarding (raw adv passthrough) → phone scan-cadence reduction setting.

**Phase 3 — sentinel mode + advanced correlation**
- Sentinel pairing (assign fixed location), backlog sync + UserPath backfill geotagging, baseline learning at sentinel site.
- Cross-radio identity clustering (reuse `linkedDeviceId` + a new `CrossRadioCorrelator`; opt-in "advanced heuristics" toggle).
- Data-frame MAC capture, 802.15.4 (C6) exploration, per-observer RSSI normalization model.

---

## 13. Fork base: `tailbait-esp32` (existing sibling project)

A repo already exists ([tailbait-esp32](https://github.com/aljazceru/tailbait-esp32)): a single-file Arduino sketch (~950 lines) porting the **entire detection core** to the LILYGO TTGO T-Display v1.1. It is *not* a companion device — it's a **standalone self-contained detector**: no phone involved, places computed from surrounding-AP fingerprints (Jaccard similarity) instead of GPS, full 7-factor threat score ported, whitelist + buttons + ST7789 UI, serial JSON event stream.

### 13.1 What it already solves (the hard parts)

| Problem | Status in fork | Notes |
|---|---|---|
| WiFi+BLE on one radio | ✅ working time-slicer | BLE 12 s → sniff 8 s → AP scan ~2 s → analyze, with full `esp_wifi`/`NimBLEDevice` init/deinit dance between phases — the fiddly part, already debugged live |
| Promiscuous frame parsing | ✅ | probe-req + station frames, addr2 extraction, randomized-MAC flag (locally-administered bit), directed-probe SSID → shadow identity |
| Tracker fingerprinting | ✅ | service-UUID table (FD5A/FD6F/FEED/FE8C/…) matching app's `TrackerServiceDetector`, Apple 0x12 Find My parsing with pubkey fingerprint key |
| Place model without GPS | ✅ | top-8 BSSIDs by RSSI, Jaccard ≥ 0.90 = same place — exactly what a fixed sentinel needs |
| Threat scoring | ✅ | same weights as `ThreatScoreCalculator.calculateEnhanced()` |
| Whitelist persistence | ✅ | NVS via `Preferences` |
| Reproducible testing | ✅ | `sim tracker <MAC>` injects synthetic stalker at 3 synthetic places |

Verified live per README: real devices seen, real multi-place alert fired.

### 13.2 Architectural mismatch — and the reframe

The companion design (§6) is *sensor-on-device*: firmware stays dumb, streams raw records, phone is the brain with real GPS. tailbait-esp32 is *brain-on-device*: no phone, no GPS, edge detection.

These are opposite designs **per mode**, but complementary **on one board**:

- **Carry mode** → companion wins (phone supplies GPS, parsing pipeline, DB, UX; ESP32 streams raw records).
- **Sentinel mode** (§2, Phase 3 in §11) → standalone wins (no phone present; AP-fingerprint places are the correct location model; alert locally, sync later). **This is already ~built.**

**Fork strategy: evolve tailbait-esp32 into dual-mode firmware** (`MODE_CARRY` / `MODE_SENTINEL`, runtime-settable via control characteristic, default from NVS). Sentinel mode = current code minus display dependency. Carry mode = new GATT streaming path alongside the existing scanners.

### 13.3 Deltas required for companion (carry) mode

1. **GATT server role.** Currently NimBLE is used purely as a scanner and fully deinit'd between phases — impossible with a live link. Restructure: `NimBLEDevice::init()` once at boot, never deinit; server + client-scan coexist on one NimBLE stack; during sniff phases stop only the *client scan* and rely on Arduino core's software coexistence (`CONFIG_SW_COEXIST_ENABLE` is on in the prebuilt sdkconfig) to keep the GATT connection alive through WiFi phases at reduced throughput (irrelevant at our data rates).
2. **Channel hopping.** `wifiSniff()` never sets a channel — it sniffs whatever the STA interface sits on (ch 1 by default). Add an `esp_wifi_set_channel()` hop loop (dwell ~150–250 ms; rebalance phase budget — 8 s ≈ only ~2 sweeps of ch 1–13). Also consider seeding the hop from the last AP-scan channel ordering.
3. **Record path.** Ring buffer + binary framing per §6.2, timestamps as `millis()` + phone-sync offset (the code is already millis-based — clean fit). Local `Device`/`Place` tables bypassed in carry mode (lightweight per-MAC dedup only).
4. **Board abstraction.** Make TFT/buttons compile-time optional (`HAS_DISPLAY`) so plain S3/C3/C6 boards work; TTGO keeps its UI as a status page (link state, battery, records/s).
5. **Project structure.** Split the single `.ino` into modules (`radio_wifi`, `radio_ble`, `brain`, `gatt_link`, `ui`, `console`) — ideally a PlatformIO project with per-board envs, keeping Arduino framework + NimBLE-Arduino.
6. **Backlog storage.** LittleFS append-log with checksummed records for link drops / sentinel sync (Phase 2 of firmware).

### 13.4 Consistency bugs found while comparing to the app (fix in fork)

- **Find My offsets are off by one/two vs the app.** App convention (`ManufacturerDataParser.FindMyPayload`): payload after company ID = `[0]=type 0x12, [1]=length, [2]=status (0x04 = separated), [3..8] = 6-byte fingerprint` → in the sketch's `md` coordinates (which include the 2-byte company ID): status = `md[4]`, fingerprint = `md[5..10]`. The sketch reads `separated = md[3] & 0x04` (the **length byte**) and fingerprints from `md[4]` (status byte + 4 key bytes). The live test passed because `sim` injects into the device table, bypassing the adv parser, and the injected-adv test likely crafted payloads matching these offsets. Fix and re-verify against a real AirTag capture.
- **Probe IE parsing assumes SSID is the first IE** (`f[24]/f[25]` fixed offsets). True for nearly all probe requests, but iterate IEs properly in the fork.
- `recordWifiDev()`'s `isProbe` parameter is vestigial (always `false`, probes return earlier).
- Shadow-SSID identity: device uses a 16-bit djb2 hash as the key; in carry mode forward the raw SSID and let the phone compute the fingerprint (per §6.3, reuse `payloadFingerprint` machinery); keep the hash only for sentinel-mode local dedup.
- Whitelist authority: in carry mode the phone's DB is authoritative; the NVS whitelist should only gate sentinel-mode local alerts.

### 13.5 Revised effort estimate (replaces §11 firmware lines)

- ~~Firmware MVP: a few weekends~~ → **~1–2 weekends**: sniffer/scheduler/tracker tables already exist and are hardware-verified; new work is GATT server + framing + hop loop + mode switch + module split.
- Sentinel mode moves from Phase 3 to **nearly free** (it's the current behavior + backlog sync).
- Phone-side ingest unchanged (1–2 weeks part-time).

### 13.6 Fork mechanics

**Update: forked.** The restructure lives in [tailbait-companion](https://github.com/aljazceru/tailbait-companion) (PlatformIO project, modules split per §13.3, Find My + IE fixes applied, channel hopping added, GATT link implemented, all four board envs building green). The original `tailbait-esp32` stays untouched as the hardware-verified v0 baseline.

---

## 14. Bottom line

The idea is sound and unusually well-matched to this codebase: the sensor boundary is clean (raw frames over GATT), the ingest boundary already exists in spirit (`processScanResultsInternal`), and the detection model (multi-location + fingerprints) is radio-agnostic. The honest framing for users: *the phone stays the brain, the ESP32 becomes an always-on pair of eyes on a radio spectrum the phone is deliberately blinded to* — with the biggest immediate wins being continuous unthrottled BLE coverage and a whole new class of WiFi tails (hotspots, laptops, IoT) that no mainstream anti-stalking tool currently sees.
