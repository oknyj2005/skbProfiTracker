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
| Mining consumable confirmation | A right click is booked when a server inventory update confirms the decrease in the hand that used the item. Client-side block placement predictions do not count. |
| Hollows-only items | `OIL_BARREL` only counts inside Crystal Hollows, within 8 s of a treasure chest announcement |

## Time

Active seconds are driven by strong signals such as mining and attacking. Pickups can extend an
active clock. The grace window is 60 s. An active normal Mining clock carries its remaining grace
across confirmed Dwarven Mines–Mineshaft transfers, including the loading time up to
the original deadline. Coordinate jumps within the Glacite route also retain that deadline. Walking
does not refresh the window. An open Slayer boss fight keeps its active clock alive.

## Testing without the game

`ScanRules` and `CaptureBuffer` hold the decision table and the in-flight bookkeeping, and neither
imports Minecraft. `tools/sim` replays them with fake data:

```bash
bash tools/sim/run.sh
```

`tools/deploy.sh` runs the same checks before it touches the client's `mods` folder, so a build
whose decision table regressed never reaches the game.

## 自动压缩（compactor）

触发条件由 Hypixel 决定，不是玩家每挖一块就压：

- 该物品在 sack 里达到 **25% 容量**，并且该物品开启了自动压缩，才会批量压一次
- 因此一次压缩量很大（例如 1,520 个 Flawed → 19 个 Fine）

`[Sacks]` 消息对两种情况的表达不同，代码三条路径对应处理：

| 情况 | 消息形态 | 处理 |
|---|---|---|
| 原料压完后还有剩余 | 原料只出现**净值**，不出现在 Removed | 按净值记账即可，不会重复 |
| 原料被压光（净减） | `Removed: -N 原料` 且 `Added: +X 产物` | `[sacks] compaction`：同条配对，冲减账本 |
| 两半分在不同消息 | 一条只有产物，另一条只有原料减少 | `[sacks] pool`：原料减少进 10 分钟池子，产物入账时按压缩比冲减 |

跨消息池只在同一批次里确实发生过压缩、且被扣的 key 属于某个产物的原料时才启用（`ItemTable.isCompactionParent`），避免把「玩家取出原料」误当成压缩。

日志（`/skbprofit debug` 打开 verbose 可看全部）：

```
[sacks] compaction -1520 FLAWED_PERIDOT_GEM for +19 FINE_PERIDOT_GEM
[sacks] pool -16000 ROUGH_AMETHYST_GEM for +200 FLAWED_AMETHYST_GEM
```
