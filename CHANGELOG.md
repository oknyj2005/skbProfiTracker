# Changelog

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
