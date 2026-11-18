"""Build src/main/resources/assets/skbprofitracker/items.json.

Slayer rows come from docs/SLAYER_TABLE.md, mining rows are listed below and
each price key is validated against data/bz.json so a typo cannot silently
produce an unpriced item.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SLAYER_DOC = ROOT / "docs" / "SLAYER_TABLE.md"
BAZAAR = ROOT / "data" / "bz.json"
OUT = ROOT / "src" / "main" / "resources" / "assets" / "skbprofitracker" / "items.json"

GEMS = [
    "AMBER", "AMETHYST", "AQUAMARINE", "CITRINE", "JADE", "JASPER",
    "ONYX", "OPAL", "PERIDOT", "RUBY", "SAPPHIRE", "TOPAZ",
]
GEM_TIERS = [("Rough", "ROUGH"), ("Flawed", "FLAWED"), ("Fine", "FINE"), ("Flawless", "FLAWLESS")]

ORES = [
    ("Mithril", "MITHRIL_ORE", "Enchanted Mithril", "ENCHANTED_MITHRIL", 160),
    ("Titanium", "TITANIUM_ORE", "Enchanted Titanium", "ENCHANTED_TITANIUM", 160),
    ("Glacite", "GLACITE", "Enchanted Glacite", "ENCHANTED_GLACITE", 160),
    ("Umber", "UMBER", "Enchanted Umber", "ENCHANTED_UMBER", 160),
    ("Tungsten", "TUNGSTEN", "Enchanted Tungsten", "ENCHANTED_TUNGSTEN", 160),
    ("Hard Stone", "HARD_STONE", "Enchanted Hard Stone", "ENCHANTED_HARD_STONE", 576),
    # The Dwarven Mines are mostly vanilla ore veins, and Compact turns those into enchanted
    # items just like it does for mithril, so both halves of each pair belong in the table.
    ("Coal", "COAL", "Enchanted Coal", "ENCHANTED_COAL", 160),
    ("Iron Ingot", "IRON_INGOT", "Enchanted Iron Ingot", "ENCHANTED_IRON", 160),
    ("Gold Ingot", "GOLD_INGOT", "Enchanted Gold Ingot", "ENCHANTED_GOLD", 160),
    ("Lapis Lazuli", "INK_SACK:4", "Enchanted Lapis Lazuli", "ENCHANTED_LAPIS_LAZULI", 160),
    ("Emerald", "EMERALD", "Enchanted Emerald", "ENCHANTED_EMERALD", 160),
    ("Diamond", "DIAMOND", "Enchanted Diamond", "ENCHANTED_DIAMOND", 160),
    ("Redstone Dust", "REDSTONE", "Enchanted Redstone Dust", "ENCHANTED_REDSTONE", 160),
    ("Nether Quartz", "QUARTZ", "Enchanted Nether Quartz", "ENCHANTED_QUARTZ", 160),
    ("Obsidian", "OBSIDIAN", "Enchanted Obsidian", "ENCHANTED_OBSIDIAN", 160),
    ("Cobblestone", "COBBLESTONE", "Enchanted Cobblestone", "ENCHANTED_COBBLESTONE", 160),
    ("Sulphur", "SULPHUR", "Enchanted Sulphur", "ENCHANTED_SULPHUR", 160),
]

# (display name, price key)
MINING_SINGLES = [
    ("Sorrow", "SORROW"),
    ("Volta", "VOLTA"),
    ("Sludge Juice", "SLUDGE_JUICE"),
    ("Yoggie", "YOGGIE"),
    ("Goblin Egg", "GOBLIN_EGG"),
    ("Green Goblin Egg", "GOBLIN_EGG_GREEN"),
    ("Blue Goblin Egg", "GOBLIN_EGG_BLUE"),
    ("Red Goblin Egg", "GOBLIN_EGG_RED"),
    ("Yellow Goblin Egg", "GOBLIN_EGG_YELLOW"),
    ("Treasurite", "TREASURITE"),
    ("Glacite Jewel", "GLACITE_JEWEL"),
    ("Corleonite", "CORLEONITE"),
    ("Robotron Reflector", "ROBOTRON_REFLECTOR"),
    ("Superlite Motor", "SUPERLITE_MOTOR"),
    ("Control Switch", "CONTROL_SWITCH"),
    ("Electron Transmitter", "ELECTRON_TRANSMITTER"),
    ("FTX 3070", "FTX_3070"),
    ("Synthetic Heart", "SYNTHETIC_HEART"),
    ("Bejeweled Handle", "BEJEWELED_HANDLE"),
    ("Frozen Scute", "FROZEN_SCUTE"),
    ("Mithril Plate", "MITHRIL_PLATE"),
    ("Tungsten Plate", "TUNGSTEN_PLATE"),
    ("Umber Plate", "UMBER_PLATE"),
    ("Refined Mithril", "REFINED_MITHRIL"),
    ("Refined Titanium", "REFINED_TITANIUM"),
    ("Refined Umber", "REFINED_UMBER"),
    ("Refined Tungsten", "REFINED_TUNGSTEN"),
    ("Glacite Amalgamation", "GLACITE_AMALGAMATION"),
    ("Dwarven O's Metallic Minis", "DWARVEN_OS_METALLIC_MINIS"),
    ("Suspicious Scrap", "SUSPICIOUS_SCRAP"),
    ("Starfall", "STARFALL"),
    ("Glossy Gemstone", "GLOSSY_GEMSTONE"),
    ("Gemstone Mixture", "GEMSTONE_MIXTURE"),
    ("Jaderald", "JADERALD"),
    ("Divan's Alloy", "DIVAN_ALLOY"),
    ("Divan Fragment", "DIVAN_FRAGMENT"),
    ("Quick Claw", "PET_ITEM_QUICK_CLAW"),
    ("Pickonimbus 2000", "PICKONIMBUS"),
    ("Bob-omb", "BOB_OMB"),
    ("Prehistoric Egg", "PREHISTORIC_EGG"),
    ("Oil Barrel", "OIL_BARREL"),
    ("Claw Fossil", "CLAW_FOSSIL"),
    ("Spine Fossil", "SPINE_FOSSIL"),
    ("Tusk Fossil", "TUSK_FOSSIL"),
    ("Webbed Fossil", "WEBBED_FOSSIL"),
    ("Ugly Fossil", "UGLY_FOSSIL"),
    ("Clubbed Fossil", "CLUBBED_FOSSIL"),
    ("Footprint Fossil", "FOOTPRINT_FOSSIL"),
    ("Umber Key", "UMBER_KEY"),
    ("Tungsten Key", "TUNGSTEN_KEY"),
    ("Skeleton Key", "SKELETON_KEY"),
]

# Essences come out of Crystal Hollows chests and land in the inventory as normal items, so a
# display-name entry is all we need. Sun Gecko Essence is Rift-only and has no bazaar price.
ESSENCES = [
    ("Crimson Essence", "ESSENCE_CRIMSON"),
    ("Diamond Essence", "ESSENCE_DIAMOND"),
    ("Dragon Essence", "ESSENCE_DRAGON"),
    ("Forest Essence", "ESSENCE_FOREST"),
    ("Fossil Essence", "ESSENCE_FOSSIL"),
    ("Gold Essence", "ESSENCE_GOLD"),
    ("Ice Essence", "ESSENCE_ICE"),
    ("Safari Essence", "ESSENCE_SAFARI"),
    ("Spider Essence", "ESSENCE_SPIDER"),
    ("Undead Essence", "ESSENCE_UNDEAD"),
    ("Wither Essence", "ESSENCE_WITHER"),
]

# Corpse drops that are only sold on the auction house, so they never appear in the bazaar.
CORPSE_EXTRAS = [
    ("Littlefoot Fluff", "LITTLEFOOT_FLUFF"),
    ("Caged Wisp", "CAGED_WISP"),
    ("Frostbitten Dye", "DYE_FROSTBITTEN"),
    ("Shattered Locket", "SHATTERED_PENDANT"),
]

# Consumables eaten while mining. They are not loot, but the inventory scanner has to know the
# display name before it can see the stack vanish, and the cost ledger prices them from the
# shop they are bought at rather than from the (much thinner) auction market.
MINING_CONSUMABLES = [
    ("Fiesta Flask", "FIESTA_FLASK"),
    ("Chilled Pristine Potato", "CHILLED_PRISTINE_POTATO"),
    ("Filet O' Fortune", "FILET_O_FORTUNE"),
    ("Powder Pie", "MINING_PUMPKIN"),
]

# Books that only ever drop from one specific source, so there is no need to guess a name from
# the enchantment id. Ice Cold I is the only book a frozen corpse prints.
FIXED_BOOKS = [
    ("Enchanted Book (Ice Cold I)", "ENCHANTMENT_ICE_COLD_1"),
    # Inferno Demonlord now drops the higher Smoldering tiers (0.27.3 loot table change).
    ("Enchanted Book (Smoldering II)", "ENCHANTMENT_SMOLDERING_2"),
    ("Enchanted Book (Smoldering III)", "ENCHANTMENT_SMOLDERING_3"),
    ("Enchanted Book (Smoldering IV)", "ENCHANTMENT_SMOLDERING_4"),
    ("Enchanted Book (Smoldering V)", "ENCHANTMENT_SMOLDERING_5"),
    ("Enchanted Book (Smoldering VI)", "ENCHANTMENT_SMOLDERING_6"),
]

ROW = re.compile(r"^\|\s*(.+?)\s*\|\s*`([^`]+)`\s*\|\s*(BZ|LBIN)\s*\|")

NEU_ITEMS = ROOT / "data" / "neu_min" / "items"
NEU_COLORS = ROOT / "data" / "neu_colors.json"

RARITY_COLORS = {
    "COMMON": "§f",
    "UNCOMMON": "§a",
    "RARE": "§9",
    "EPIC": "§5",
    "LEGENDARY": "§6",
    "MYTHIC": "§d",
    "DIVINE": "§b",
    "SPECIAL": "§c",
    "VERY SPECIAL": "§c",
}

BOOK_LEVEL_COLORS = {1: "§f", 2: "§f", 3: "§f", 4: "§f", 5: "§a", 6: "§9"}

GEM_TIER_COLORS = {"Rough": "§f", "Flawed": "§a", "Fine": "§9", "Flawless": "§5"}

ROMAN = {
    "I": 1, "II": 2, "III": 3, "IV": 4, "V": 5,
    "VI": 6, "VII": 7, "VIII": 8, "IX": 9, "X": 10,
}

PET_NAME = re.compile(r"^\[Lvl \d+\] (.+) \(([A-Z ]+)\)$")
BOOK_NAME = re.compile(r"^Enchanted Book \(.+ (I|II|III|IV|V|VI|VII|VIII|IX|X)\)$")
LEADING_SYMBOL = re.compile(r"^[^A-Za-z\[\(\d]+")


def strip_symbols(name: str) -> str:
    """NEU prefixes runes with a glyph such as a star; ours do not."""
    return LEADING_SYMBOL.sub("", name).strip()


def neu_colors() -> dict[str, str]:
    """display name -> leading colour codes, taken from NEU's already coloured names."""
    if NEU_COLORS.is_file():
        raw = json.loads(NEU_COLORS.read_text(encoding="utf-8"))
        return {strip_symbols(name): color for name, color in raw.items()}

    colors: dict[str, str] = {}
    if not NEU_ITEMS.is_dir():
        return colors

    for path in NEU_ITEMS.glob("*.json"):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue

        display = data.get("displayname") or ""
        i = 0
        while i + 1 < len(display) and display[i] == "§":
            i += 2
        if i == 0 or i >= len(display):
            continue
        colors.setdefault(strip_symbols(display[i:]), display[:i])

    return colors


def color_for(item: dict, colors: dict[str, str]) -> str | None:
    name = item["name"]

    # Ultimate enchantments are EPIC no matter the level, which is also why Feesh's
    # Flash book is purple.
    if item["key"].startswith("ENCHANTMENT_ULTIMATE_"):
        return "§d"

    if name in colors:
        return colors[name]

    for tier_name, tier_color in GEM_TIER_COLORS.items():
        if name.startswith(f"{tier_name} ") and name.endswith(" Gemstone"):
            return tier_color

    pet = PET_NAME.match(name)
    if pet:
        return RARITY_COLORS.get(pet.group(2))

    book = BOOK_NAME.match(name)
    if book:
        return BOOK_LEVEL_COLORS.get(ROMAN[book.group(1)])

    # Runes are EPIC on Hypixel and NEU only names the base rune.
    if name.endswith(" Rune I") or " Rune " in name:
        return "§5"

    return None


def slayer_rows() -> list[dict]:
    items: list[dict] = []
    seen: set[str] = set()
    for line in SLAYER_DOC.read_text(encoding="utf-8").splitlines():
        match = ROW.match(line)
        if not match:
            continue
        name, key = match.group(1), match.group(2)
        if name in seen:
            continue
        seen.add(name)
        items.append({"name": name, "key": key})
    return items


def mining_rows() -> list[dict]:
    items: list[dict] = []
    # Gemstone tiers compact 80:1 (Rough -> Flawed -> Fine -> Flawless). The metadata lets a
    # sack line such as `-1,520 Flawed` + `+19 Fine` cancel the raw units already credited.
    previous_tier: tuple[str, str] | None = None
    for tier_name, tier_key in GEM_TIERS:
        for gem in GEMS:
            entry = {"name": f"{tier_name} {gem.title()} Gemstone", "key": f"{tier_key}_{gem}_GEM"}
            if previous_tier is not None:
                entry["compactsFrom"] = f"{previous_tier[0]} {gem.title()} Gemstone"
                entry["compactRatio"] = 80
            items.append(entry)
        previous_tier = (tier_name, tier_key)
    for ore_name, ore_key, enchanted_name, enchanted_key, ratio in ORES:
        items.append({"name": ore_name, "key": ore_key})
        items.append(
            {
                "name": enchanted_name,
                "key": enchanted_key,
                "compactsFrom": ore_name,
                "compactRatio": ratio,
            }
        )
    for name, key in MINING_SINGLES:
        items.append({"name": name, "key": key})
    for name, key in CORPSE_EXTRAS:
        items.append({"name": name, "key": key})
    for name, key in FIXED_BOOKS:
        items.append({"name": name, "key": key})
    for name, key in ESSENCES:
        items.append({"name": name, "key": key})
    for name, key in MINING_CONSUMABLES:
        items.append({"name": name, "key": key})
    return items


def hypixel_npc_prices() -> dict[str, int]:
    """Item id -> NPC sell price, from the public Hypixel resource dump."""
    path = ROOT / "data" / "hypixel_items.json"
    if not path.exists():
        print("data/hypixel_items.json missing, skipping NPC prices")
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    prices: dict[str, int] = {}
    for entry in payload.get("items", []):
        price = entry.get("npc_sell_price")
        item_id = entry.get("id")
        if item_id and price:
            prices[item_id] = int(price)
    return prices


def main() -> int:
    known = set(json.loads(BAZAAR.read_text(encoding="utf-8"))["products"])
    merged: list[dict] = []
    seen: set[str] = set()
    for item in slayer_rows() + mining_rows():
        if item["name"] in seen:
            continue
        seen.add(item["name"])
        merged.append(item)
    items = merged

    colors = neu_colors()
    colored = 0
    uncoloured: list[str] = []
    for item in items:
        color = color_for(item, colors)
        if color:
            item["color"] = color
            colored += 1
        else:
            uncoloured.append(item["name"])
    print(f"colours: {colored}/{len(items)}, missing {len(uncoloured)}")
    for name in uncoloured:
        print("  no colour:", name)

    npc_prices = hypixel_npc_prices()
    with_npc = 0
    for item in items:
        npc = npc_prices.get(item["key"].split(";")[0])
        if npc:
            item["npc"] = npc
            with_npc += 1
    print(f"npc prices: {with_npc}/{len(items)}")

    missing = [item["key"] for item in items if item["key"] not in known]
    if missing:
        print("keys that are not bazaar products (auction/NPC only):")
        for key in sorted(set(missing)):
            print("  ", key)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps({"items": items}, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print(f"\nwrote {len(items)} items to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
