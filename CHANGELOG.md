# Changelog

## 0.1.10

### English

- The **HUD** supports scrolling through item rows and hovering over costs for a breakdown. Gemstone names are shorter, and tracker chat messages carry a coin icon.
- **HUD controls and commands** share slice selection, view and price modes, pause, and Nucleus timing. The HUD consumes clicks on visible controls before they reach inventory slots. Item edits stay in the displayed slice. Total resets require chat confirmation for the original activity and slice.
- **Mining time** carries across Dwarven Mines and Glacite Mineshaft transfers. Confirmed transfer time counts up to the original 60-second grace deadline.
- **Mining consumables** are charged after a server inventory update confirms use in the main hand or offhand.
- **Manual pause** now also stops Sack batches, Corpse and Nucleus chat loot, and Slayer completion counters from updating the ledger.
- The **HUD reuses text measurements and placements** between updates. Activity, purse, and region checks share one sidebar snapshot per tick. Inventory scans read the relevant item data fields and lore lines.

### 简体中文

- **面板**支持滚动查看物品明细，悬停成本行可查看分项。宝石名称更短，追踪器的聊天消息新增金币图标。
- **面板和命令**共用切片、视图、价档、暂停和 Nucleus 计时规则。面板先处理命中控件的点击，再由背包处理其余点击。物品修正绑定显示的切片，Total 重置需在聊天栏确认原活动和切片。
- **挖矿计时**会在矮人矿区与冰洞往返时接续原有的 60 秒窗口。确认换服后，会补记原截止点之前的加载时间。
- **挖矿消耗品**在服务器背包更新确认主手或副手已使用后记成本。
- **手动暂停**期间，Sacks、尸体和 Nucleus 的聊天战利品，以及 Slayer 完成计数都会停止入账。
- **面板复用文字测宽和位置**。活动、钱包和区域判断共用每 tick 的计分板快照，背包扫描只解析所需的物品数据字段和说明行。

## 0.1.9

### Added
- Track Sven Packmaster Tier V drops and Slayer Fragments seen on Hypixel Alpha.
- Show elapsed time in the Nucleus overlay. Enable the timer with `/skbprofit nucleustime on`.

### Changed
- Edit item quantities within an individual slice. The `All` view is read-only, and `[x]` removes the quantity shown on the row.
- Reset targets the selected activity, slice, and view. Chat confirmation keeps that target if you switch views.
- Using a mining consumable can start the mining timer. The cost is recorded even if the mining bucket has yet to open.

### Fixed
- Sack deposits deduct only the matching quantity. Withdrawals reverse pickup credits in the original slice, even after switching views.
- Sack compaction credits the finished item and offsets ingredients across messages within the same slice.
- Moving a mining potion into an open container avoids a consumption charge. Duplicate potion signals are filtered within 2.5 seconds.
- Timing and idle indicators follow the active slice, and stale signals are cleared when the activity changes.

## 0.1.8

### Fixed
- Compaction that reports its two halves in different `[Sacks]` lines no longer counts twice: the
  raw units are parked in a ten minute pool and the product is netted against them (`[sacks] pool`).
- A stack pulled out of a sack and put back a minute later is not credited as fresh loot any more.
- The overlay reset button only ever clears the session. Clearing the accumulated total now requires
  `/skbprofit reset total` and a confirmation in chat, because clicking reset while the total was on
  screen used to wipe the history by accident.
- The session no longer starts on a single stray click: a second strong signal within three seconds
  is required, and a teleport resets the signal so changing islands does not leave the timer armed.

### Added
- `Littlefoot Fluff` (301 tracked items).

## 0.1.7

### Fixed
- Cost items (corpse keys, gummi bears, the four mining consumables) are never booked as income
  any more. Putting a stack of keys into a sack used to be read as "8x Tungsten Key" of profit.
- The `Sack of Sacks` screen is recognised as a sack, so `Insert inventory` deposits are netted
  out instead of being credited a second time.
- Deleting or adjusting a row in one view now applies to session and total together; previously
  the correction only reached the mode you were looking at.
- Buying from a Hypixel shop no longer starts the timer: the click that closes the shop was
  reaching the world as a block break.

### Added
- Essence gains are read from the action bar (`+100 Ice Essence`) and priced through their bazaar
  key, covering all eleven essences. They never touch the inventory or a sack, so this is the only
  readable channel.
- Inventory items whose display name is unknown now fall back to their `custom_data.id`, and a
  miss logs `[scan] unknown item '<name>' id=<id>` once instead of disappearing silently.
- Always-on state transitions (`[state] <bucket>|<counting>|<paused> world=… zone=… elapsed=…`)
  and a signal source in `[signal]` lines, so a session that keeps counting can be explained.
- `tools/deploy.sh` refuses to install a jar older than the newest source file.

## 0.1.6

### Fixed
- Withdrawing from a sack and putting the stack back by hand no longer produces fake profit. The
  deposit is remembered per unit and consumed against the next `[Sacks] +` line, so a small line
  can no longer swallow the whole record.
- Items refunded by a consumable no longer count: drinking a player-head consumable (Fiesta Flask)
  makes the client predict a block placement and used to be charged twice.
- Chat-style menus (`SkyBlock Menu`, `Fast Travel`, `Collections`) no longer count their contents
  as loot.
- Only the sack screen is treated as a manual deposit. Drill anvil, forge and pets consume or move
  items, and treating those as deposits cancelled the next real one.
- `Oil Barrel` only counts inside Crystal Hollows, within 8 seconds of
  `You uncovered a treasure chest!`, so refuelling and sack shuffles can no longer book it in
  Dwarven Mines.
- Refuelling no longer appends a fake pickup to the mining ledger.

### Changed
- Fishing suppresses the overlay again, but only after the bobber has been in the water for 2
  seconds: a quick cast to swap pets counts as mining / slayer, not fishing.
- `/skbprofit debug` now also enables verbose logging; per-scan lines stay silent otherwise.

### Added
- `docs/CAPTURE_PIPELINE.md` describes the three entry points, the fences and the attribution rules.
- `tools/sim` replays the scanner decision table with fake data (33 checks, no game needed).
- `tools/deploy.sh` refuses to install a build whose checks fail.

## 0.1.5
- Auction purchases (BIN claim) no longer count as loot.
- Slayer: boss kill counter, per-type buckets and a filter, `/skbprofit types`.
- Mining: `Corpse` / `Nucleus` slices, in-panel picker, per-slice costs.
- Price mode is toggled by clicking the `[insta]` / `[offer]` badge on the profit line.
- `SHA256SUMS.txt` ships with every release.

## 0.1.4
- Double charging fixed for head-based consumables.
- Prices fall back to NPC sell price when the market price is lower.

## 0.1.3
- Mining slice picker, per-slice resets, sack grab fix.

## 0.1.2
- First public release.
