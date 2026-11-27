# skbProfiTracker

**English** · [简体中文](README.zh-CN.md)

A client-side Fabric mod for Hypixel SkyBlock on **Minecraft 26.2**. Track drops, explicit costs, and active time while mining or doing Slayer, including the Rift.

## Install

Download the JAR from [GitHub Releases](https://github.com/oknyj2005/skbProfiTracker/releases/latest). Each release includes `SHA256SUMS.txt` so you can verify the download.

Put the JAR in your Minecraft instance's `mods` folder with **Fabric API** and **Fabric Language Kotlin** for Minecraft 26.2. The mod requires **Fabric Loader 0.18.1 or later** and **Java 25 or later**.

## What the numbers mean

- **Profit** is the current value of recorded drops and coins. When there are recorded costs with a value, the HUD shows **Costs** and **Net profit**. Hover over the costs row in inventory to see the breakdown.
- Market prices come from the selected Bazaar mode (**Insta Sell** or **Sell Offer**), then Auction House lowest BIN if available. NPC sell value acts as a floor. The mod stores counts, time, and recorded coin amounts, so previous drops are valued at current prices.
- **Session** covers the current game launch. **Total** accumulates across launches. Mining has separate Mining, Corpse, and Nucleus slices. Slayer has one slice per boss type, and the Rift has its own tracker.
- Active time uses a **60-second grace window**. Two mining or combat signals within three seconds can start the timer. A confirmed mining consumable use can start Mining immediately. Pickups extend a running timer. Confirmed travel between Dwarven Mines and a Glacite Mineshaft carries over the time remaining in the original window.
- Nucleus timing is available in Crystal Hollows. Enable it from the HUD or with `/skbprofit nucleustime on`.

## HUD controls

Open inventory or chat to switch views, choose a slice, pause, or click the price badge. To browse more than 10 detailed item rows, hover over the list and scroll.

In inventory, hover over an item in a specific slice to use **[+]**, **[-]**, or **[x]**. Both Session and Total receive the correction for that slice. **[x]** removes the shown quantity. **All** combines the slices and keeps item editing disabled.

Click the Session reset button twice to confirm. For a Total reset, use the confirmation in chat. It keeps the original activity and slice if you change views. Resetting Total also clears Session for that target.

## Commands

| Command | Action |
| --- | --- |
| `/skbprofit` | Show the current tracker status |
| `/skbprofit gui` | Move, scale, or align the HUD |
| `/skbprofit mode [session\|total]` | Switch the displayed view |
| `/skbprofit price [insta\|offer]` | Switch the Bazaar pricing mode |
| `/skbprofit types` | List slices for the current activity in chat |
| `/skbprofit mining [slice]` | List or select a Mining slice. Use `all` to combine slices |
| `/skbprofit slayer [type]` | List or select a Slayer type. Use `all` to combine types |
| `/skbprofit nucleustime [on\|off]` | Enable or disable Nucleus timing |
| `/skbprofit pause` | Pause or resume tracking |
| `/skbprofit reset [session\|total]` | Request a reset of the selected activity and slice |
| `/skbprofit costs` | Show the cost breakdown in chat |
| `/skbprofit loot [n]` | List recent chat loot entries |
| `/skbprofit debug` | Toggle diagnostics and show tracker state and the loaded build |

## What's new in 0.1.11

- Bazaar deliveries and delayed manual Sack deposits are excluded from mining profit by quantity.
- Sack compaction reconciles existing stock, and Fossil Excavator reward deliveries are excluded from mining profit.
- Slayer costs use net coin flow, and Crystal Hollows mining starts manually.

See the [changelog](CHANGELOG.md) for the full release notes.

## Build and test

Build with **JDK 25**.

```bash
./gradlew :26.2-fabric:build
bash tools/sim/run.sh
```

On Windows, use `.\gradlew.bat :26.2-fabric:build` from PowerShell and run the simulation script with Git Bash. The JAR is written to `build/versions/`. The offline checks cover capture rules, consumable confirmation, travel timing, and HUD controls.

Read the [handover](docs/HANDOVER.md), [capture pipeline](docs/CAPTURE_PIPELINE.md), and [Slayer drop table](docs/SLAYER_TABLE.md) for project details.

## Credits and license

The architecture and HUD framework are derived from [Feesh](https://github.com/Sleepy-Panda/Feesh). Drop and item references include SkyHanni and NotEnoughUpdates. Market prices come from the Hypixel Bazaar API and the EliteSkyBlock auction API.

Licensed under [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
