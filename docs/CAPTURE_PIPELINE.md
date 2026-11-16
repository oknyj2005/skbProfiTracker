# How loot is captured

This is the short version of the pipeline, written down so future changes have a map. Every rule
here exists because a real duplicate or a real miss was traced back to that exact spot.

## Three ways an item can arrive

| Entry point | Trigger | Latency |
|---|---|---|
| Inventory diff | `ClientTickEvent`, every 4 ticks (200 ms) | up to 200 ms |
| Sacks message | `[Sacks] +N Name (Sack)` | 0–60 s, Hypixel batches these |
| Loot block | `LAPIS CORPSE LOOT!` / `CRYSTAL NUCLEUS LOOT BUNDLE` | immediate |

All three end in `BucketLedger.add(activity, key, amount)`, which is the only writer to the ledger.

## One gate for names

`ItemTable` maps the in-game display name (`Enchanted Mithril`, `[Lvl 1] Ghoul (LEGENDARY)`,
`Sharpened Claw Rune I`) to a price key (`ENCHANTED_MITHRIL`, `GHOUL;4`, `SNAKE_RUNE;1`) plus a
colour. Anything not in the table is dropped and reported once per name per five minutes:

```
[skbprofit] unknown loot: Glacite Powder x8870 (not in the item table, not counted)
```

When a new drop appears, the table is the file to edit — not the scanners.

## Attribution: which bucket gets it

Mining splits into `Mining`, `Corpse` and `Nucleus`. Only the loot-block path can write the latter
two: the chat header is the only trustworthy statement about where an item came from. The inventory
diff always writes `Mining`, and a "credit" mechanism moves a head start back when a loot block
arrives right after a stack was picked up.

Slayer picks the active quest type (`Revenant Horror`, `Tarantula Broodfather`, …) from the
sidebar, and keeps the last one seen so a boss's loot still lands after the quest line disappears.

## The fences

Every rule below suppresses a *false* credit. They are ordered by how often they fire.

| Fence | Why it exists |
|---|---|
| Session gate | The overlay must be visible and not paused, or nothing is booked |
| Container offset | A stack moving from a chest into the inventory is not loot (`increase − min(increase, containerDecrease)`) |
| Armour folded into the container | Taking a helmet off is not loot; only five extra slots, only while the inventory screen is open |
| Screen transition rebuilds the baseline | The click that closes a menu lands between two scans |
| Conversion screens skipped | Crafting / anvil / enchanting / brewing / furnace turn A into B |
| Menu screens skipped | Skyblock Menu, Fast Travel, Collections hold no items |
| Screen close grace (1.5 s) | The client rewrites slots right after a menu closes |
| Sacks screen: increases ignored | You cannot mine with the sack open, so every increase is a withdrawal |
| Sack deposit netting | A manual deposit is remembered up to 120 s and consumed *per unit* against the next `[Sacks] +` line |
| Removal credit (10 s) | Drop and pick up, or the compactor: 160 ore → 1 block |
| Supercraft guard | The supercrafted stack appears in the sacks list and must not count twice |
| Auction purchase guard | `You purchased X for N coins!` / `You claimed X from …'s auction!` fence the item for 10 s |
| Head-consumable dedupe (5 s) | Drinking a player-head consumable makes the client predict a block placement |
| Hollows-only items | `OIL_BARREL` only counts inside Crystal Hollows, within 8 s of a treasure chest announcement |

## Time

Active seconds only: a strong signal (block break, attack) starts the clock, a weak signal (pickup,
damage) only extends it. Grace is 60 s for slayer and rift, 90 s for mining, and an open boss fight
(`Slay the boss!`) keeps it alive on its own. Only the `Mining` slice shows a per-hour number; the
other slices are totals.

## Testing without the game

`ScanRules` and `CaptureBuffer` hold the decision table and the in-flight bookkeeping, and neither
imports Minecraft. `tools/sim` replays them with fake data:

```bash
bash tools/sim/run.sh
```

`tools/deploy.sh` runs the same checks before it touches the client's `mods` folder, so a build
whose decision table regressed never reaches the game.
