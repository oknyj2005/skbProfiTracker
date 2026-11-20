# Changelog

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
