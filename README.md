# TailBait

An Android app that watches for Bluetooth devices following you around.

## What this does

TailBait scans for Bluetooth Low Energy (BLE) devices near you, remembers where it saw them, and alerts you if the same device keeps showing up at different locations. The idea is simple: if a device is at your office, then at the grocery store, then at your house, that's worth knowing about.

This covers everything that broadcasts over BLE: AirTags, Tile trackers, Chipolo, Samsung SmartTags, but also phones, headphones, smartwatches, and other Bluetooth gadgets. If it's advertising a BLE signal, TailBait can see it.

## What this does not do

Modern Bluetooth devices use anti-tracking technology. Most phones and many trackers rotate their MAC addresses periodically, which means the device looks like a "new" device every time it changes. We try to work around this through signal pattern analysis, manufacturer fingerprinting, and behavioral heuristics, but this is best-effort. A device that rotates its MAC well will likely slip through.

Specifically:

- Devices have to be actively broadcasting BLE signals to be detected. If a tracker is dormant or using classic Bluetooth, we won't see it.
- Range is roughly 30-100 meters depending on conditions. Walls, crowds, and interference all reduce this.
- MAC rotation on modern phones (iOS, recent Android) is specifically designed to prevent what we're trying to do. We catch some of these through device characteristic matching, but not reliably.
- This is not a forensic tool. It will miss things. It will also flag things that aren't threats.

If you feel unsafe, contact law enforcement. This app is one signal among many, not a replacement for professional help.

## How it works

1. The app runs BLE scans in the background (you pick how aggressively: continuous, periodic, or location-triggered).
2. Each time it sees a device, it records the device identifier, signal strength, and your GPS location.
3. Every 15 minutes, a detection algorithm checks whether any device has appeared at multiple distinct locations (default threshold: 3 separate locations at least 100 meters apart).
4. If a device crosses that threshold, the app scores it based on how many locations, how far apart they are, time correlation, and whether it looks like a known tracker type.
5. You get a notification with a threat level. You can review the device, see it on a map, or whitelist it.

## Learn mode

The first thing you should do is teach the app about your own devices. Your phone, your watch, your car's Bluetooth, your headphones - these will all trigger alerts if you don't whitelist them first.

Open Learn Mode, let it scan for a few minutes, then mark everything that's yours. Do this at home and maybe once at work to catch devices you carry between locations.

## Detection scoring

The threat score runs from 0.0 to 1.0:

- 0.75+ CRITICAL - you get an immediate alert
- 0.50-0.74 HIGH - strong warning
- 0.25-0.49 MEDIUM - notification, worth checking
- below 0.25 LOW - logged but probably not a concern

The score factors in: number of distinct locations where the device appeared, geographic distance between those locations, whether the timing correlates with your movements, and whether the device matches known tracker manufacturer IDs (Apple, Tile, Samsung, Chipolo, Google).

Known tracker types get a score boost because an unknown AirTag following you across town is more concerning than an unknown pair of headphones at two coffee shops.

## Privacy

All data stays on your phone. There are no API calls, no cloud sync, no analytics, no ads. The app needs Bluetooth and Location permissions to function, and optionally background location permission if you want it scanning while the app is closed. That's it.

## Battery

Continuous scanning uses roughly 3-5% per hour. Periodic mode drops that to 1-2%. Location-based mode (only scans when you move significantly) is under 1% per hour. The app backs off automatically when your battery is low.

## License

Apache 2.0
