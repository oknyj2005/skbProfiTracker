# skbProfiTracker

**English** | [简体中文](README.zh-CN.md)

Client-side Fabric mod for **Hypixel SkyBlock** that answers one question:

> 我现在干的这件事，每小时值多少钱？

It watches what drops into your inventory (and your Sacks), prices everything against the
Bazaar / Auction House, and divides by *active* time — so the number stays honest when you
afk, walk around, or reorganize your inventory.

Currently targeted at **mining** and **slayer** (including Rift), on **Minecraft 26.2**.

## Download

Download the mod jar and its `SHA256SUMS.txt` from the **[latest release](https://github.com/oknyj2005/skbProfiTracker/releases/latest)**.
Put the jar in `.minecraft/mods/` alongside Fabric API and Fabric Language Kotlin. Use
Minecraft 26.2 with Fabric Loader 0.18.1 or newer and Java 25 or newer.

## How it counts

* **Gross output, not net profit.** Value = `Σ amount × current price`. Consumables are
  tracked separately as costs; the headline number never has them subtracted.
* **Prices are never stored.** Only quantities and elapsed time are persisted; every redraw
  reprices against the live snapshot. Price order is Bazaar → lowest BIN → NPC sell price,
  with the NPC price acting as a floor (so an item worth less than its NPC value is still
  worth its NPC value).
* **Active time.** Two strong signals within three seconds start the clock. Using a mining
  consumable can start it immediately. Pickups extend an active clock. The idle window is
  60 seconds. Boss fights hold the clock open, and manual pause freezes it.
* **Buckets.** `Mining` / `Slayer` / `Rift` are gated by area, so the same pickaxe swing in
  the Dwarven Mines and in the Spider's Den lands in different buckets.
* **Slices.** Mining splits into `Mining` / `Nucleus` / `Corpse`, slayer into one bucket per
  slayer type. Corpse and Nucleus loot is read from the chat loot block, so chest contents
  never leak into the plain `Mining` number.

## Commands

| Command | What it does |
| --- | --- |
| `/skbprofit gui` | Move / rescale the overlay |
| `/skbprofit mode [session\|total]` | Switch the displayed mode |
| `/skbprofit price [offer\|insta]` | Switch the price source |
| `/skbprofit types` | Chat menu of the slices for the current activity |
| `/skbprofit slayer <type>` | Filter slayer by type (`all` to reset) |
| `/skbprofit mining <type>` | Filter mining by slice (`all` to reset) |
| `/skbprofit nucleustime <on\|off>` | Toggle the Nucleus timer in Crystal Hollows |
| `/skbprofit pause` | Pause / resume counting |
| `/skbprofit reset [session\|total]` | Reset the selected slice after confirmation |
| `/skbprofit loot [n]` | Dump the last loot entries the chat parser saw |
| `/skbprofit debug` | Print bucket / area / price / click diagnostics |

Open inventory or chat to interact with the overlay. Select a slice to use the `[+]`, `[-]`, and
`[x]` item controls. The buttons below switch modes, pause, or request a reset of the displayed view.

## Tests

The scanner decision table and the in-flight bookkeeping live in `capture/ScanRules.kt` and
`capture/CaptureBuffer.kt`, neither of which imports Minecraft, so they can be replayed with fake
data:

```bash
bash tools/sim/run.sh
```

`tools/deploy.sh` runs the same checks before copying a build into a client.

## Building

Requires **JDK 25** (Minecraft 26.x ships class files at major version 69).

```bash
./gradlew :26.2-fabric:build      # macOS / Linux
gradlew.bat :26.2-fabric:build    # Windows
```

The jar lands in `build/versions/` and goes into `.minecraft/mods/`.

## Repository layout

```
src/main/kotlin/…        mod sources (Fabric + Kotlin)
src/main/java/…          mixins
src/main/resources/      fabric.mod.json, mixins config, assets (item table)
versions/26.2-fabric/    per-version Gradle module
docs/                    design notes and generated item tables
tools/                   table generators and fetch scripts
```

`data/` (price and NEU snapshots) and `reference/` (third-party sources used while
reverse-engineering) are local only and git-ignored.

## Docs

* [`docs/HANDOVER.md`](docs/HANDOVER.md) — state of the project, build and deploy steps, known traps.
* [`docs/CAPTURE_PIPELINE.md`](docs/CAPTURE_PIPELINE.md) — how an item becomes profit, gate by gate.
* [`docs/DESIGN.md`](docs/DESIGN.md) — the design decisions behind the numbers.
* [`docs/SLAYER_TABLE.md`](docs/SLAYER_TABLE.md) — generated slayer drop table.

## Credits

* Architecture and UI framework are derived from **[Feesh](https://github.com/Sleepy-Panda/Feesh)**
  (Apache-2.0) — see [`NOTICE`](NOTICE).
* Slayer drop lists cross-checked against the **SkyHanni** repository constants.
* Display names come from the **NotEnoughUpdates** item repository.
* Prices: Hypixel Bazaar API and EliteSkyBlock auction API.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
