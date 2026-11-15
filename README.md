# skbProfiTracker

**English** | [简体中文](README.zh-CN.md)

Client-side Fabric mod for **Hypixel SkyBlock** that answers one question:

> 我现在干的这件事，每小时值多少钱？

It watches what drops into your inventory (and your Sacks), prices everything against the
Bazaar / Auction House, and divides by *active* time — so the number stays honest when you
afk, walk around, or reorganize your inventory.

Currently targeted at **mining** and **slayer** (including Rift), on **Minecraft 26.2**.

## Download

Grab the jar from **[Releases](https://github.com/oknyj2005/skbProfiTracker/releases)** and drop it
into `.minecraft/mods/`. No account needed — the release assets are public.

## Status

`0.1.0` — first public release. Mining and slayer work end to end; fishing, dungeons and
garden are out of scope.

## How it counts

* **Gross output, not net profit.** Value = `Σ amount × current price`. Consumables are
  tracked separately as costs; the headline number never has them subtracted.
* **Prices are never stored.** Only quantities and elapsed time are persisted; every redraw
  reprices against the live snapshot. Price order is Bazaar → lowest BIN → NPC sell price,
  with the NPC price acting as a floor (so an item worth less than its NPC value is still
  worth its NPC value).
* **Active time.** A strong signal (breaking a block, hitting a mob) starts the clock;
  pickups only extend it. The idle window is 90 s for mining and 60 s for slayer/Rift.
  Boss fights hold the clock open. Manual pause freezes everything.
* **Buckets.** `Mining` / `Slayer` / `Rift` are gated by area, so the same pickaxe swing in
  the Dwarven Mines and in the Spider's Den lands in different buckets.
* **Slices.** Mining splits into `Mining` / `Nucleus` / `Corpse`, slayer into one bucket per
  slayer type. Corpse and Nucleus loot is read from the chat loot block, so chest contents
  never leak into the plain `Mining` number.
* **No manual start.** The tracker opens itself when a bucket opens.

## Commands

| Command | What it does |
| --- | --- |
| `/skbprofit gui` | Move / rescale the overlay |
| `/skbprofit mode [session\|total]` | Switch the displayed mode |
| `/skbprofit price [offer\|insta]` | Switch the price source |
| `/skbprofit types` | Chat menu of the slices for the current activity |
| `/skbprofit slayer <type>` | Filter slayer by type (`all` to reset) |
| `/skbprofit mining <type>` | Filter mining by slice (`all` to reset) |
| `/skbprofit pause` | Pause / resume counting |
| `/skbprofit reset [total]` | Reset the current slice |
| `/skbprofit loot [n]` | Dump the last loot entries the chat parser saw |
| `/skbprofit debug` | Print bucket / area / price / click diagnostics |

The overlay is clickable while an inventory or the chat is open: click a row's `[+]` `[-]`
`[x]` to adjust it by hand, and use the buttons underneath to switch mode, pause or reset.

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

## Credits

* Architecture and UI framework are derived from **[Feesh](https://github.com/Sleepy-Panda/Feesh)**
  (Apache-2.0) — see [`NOTICE`](NOTICE).
* Slayer drop lists cross-checked against the **SkyHanni** repository constants.
* Display names come from the **NotEnoughUpdates** item repository.
* Prices: Hypixel Bazaar API and EliteSkyBlock auction API.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
