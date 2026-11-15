"""生成 Slayer 掉落表（Feesh 风格：显示名 → 价格 key）。

数据源：
  - 物品清单：SkyHanni constants/SlayerProfitTrackerItems.json
  - 显示名：NEU 仓库 items/<ID>.json 的 displayname
  - shard 映射：NEU constants/attribute_shards.json（displayName + bazaarName）
  - 价格：data/bz.json（快照）

用法：python tools/gen_slayer_table.py
输出：docs/SLAYER_TABLE.md
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NEU_DIR = ROOT / "data/neu_min"
NEU = NEU_DIR / "items"
BZ = json.loads((ROOT / "data/bz.json").read_text(encoding="utf-8"))["products"]
SLAYERS = json.loads(
    (ROOT / "reference/skyhanni/repo/SlayerProfitTrackerItems.json").read_text(encoding="utf-8")
)["slayers"]
# Rift 维度用 Motes 结算，暂不纳入
EXCLUDED = set()  # Rift 待定
SLAYERS = {k: v for k, v in SLAYERS.items() if k not in EXCLUDED}
SHARDS = {
    e["internalName"]: e
    for e in json.loads((NEU_DIR / "constants/attribute_shards.json").read_text(encoding="utf-8"))[
        "attributes"
    ]
}

ROMAN = ["", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"]

# 书在 NEU 里的 id 和游戏里显示的附魔名对不上的情况（按显示名识别，所以以显示名为准）。
BOOK_NAME_OVERRIDES = {
    "ULTIMATE_REITERATE": "Duplex",
}
RARITY = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"]
PUA = re.compile(r"[\ue000-\uf8ff]")
SMALL = {"of", "the", "and", "in", "to", "a", "an"}


def clean(text: str) -> str:
    return PUA.sub("", re.sub(r"§.", "", text)).strip()


def pretty(identifier: str) -> str:
    words = identifier.lower().split("_")
    return " ".join(w if i and w in SMALL else w.capitalize() for i, w in enumerate(words))


def read_display(item_id: str) -> str | None:
    for cand in (item_id, item_id.split(";")[0]):
        f = NEU / f"{cand}.json"
        if f.exists():
            return clean(json.loads(f.read_text(encoding="utf-8"))["displayname"])
    return None


# 2025-08-05 更新起，这两本 VII 书被 Endcap Upgrade 物品替换，书本身不再产出
ENDCAP_REPLACEMENTS = {
    "SMITE;7": ("Severed Hand", "SEVERED_HAND"),
    "ENDER_SLAYER;7": ("End Stone Idol", "ENDSTONE_IDOL"),
}


def resolve(item_id: str) -> tuple[str, str | None]:
    """返回 (识别用的显示名, bazaar product id 或 None)。"""
    if item_id in ENDCAP_REPLACEMENTS:
        return ENDCAP_REPLACEMENTS[item_id]

    base, _, suffix = item_id.partition(";")

    if base.startswith("ATTRIBUTE_SHARD_"):
        entry = SHARDS.get(item_id)
        if entry:
            return f"{entry['displayName']} Shard", entry["bazaarName"]
        return (read_display(item_id) or f"{pretty(base.removeprefix('ATTRIBUTE_SHARD_'))} Shard"), None

    if suffix.isdigit() and base.endswith("_RUNE"):
        return f"{pretty(base.removesuffix('_RUNE'))} Rune {ROMAN[int(suffix)]}", None

    if suffix.isdigit():
        name = read_display(item_id)
        if name == "Enchanted Book":
            # 附魔书在 bazaar 的 key 是 ENCHANTMENT_<附魔>_<等级>，不是 NEU 的 `<附魔>;<等级>`
            bz_key = f"ENCHANTMENT_{base}_{int(suffix)}"
            # NEU 的 id 与游戏里显示的附魔名不一定一致：ULTIMATE_REITERATE 的实际名字是
            # Duplex（lore 里写 `§d§lDuplex I`），识别靠的是显示名，所以必须用显示名。
            display = BOOK_NAME_OVERRIDES.get(base, pretty(base))
            return f"Enchanted Book ({display} {ROMAN[int(suffix)]})", (bz_key if bz_key in BZ else None)
        if name and "{LVL}" in name:
            return f"[Lvl 1] {name.replace('[Lvl {LVL}] ', '')} ({RARITY[int(suffix)]})", None

    name = read_display(item_id)
    return (name or pretty(base)), (base if base in BZ else None)


def main() -> None:
    out = [
        "# Slayer 掉落表",
        "",
        "> 物品清单取自 SkyHanni `constants/SlayerProfitTrackerItems.json`，**仅取「有哪些物品」**；",
        "> 识别与定价按 Feesh 的做法：显示名 → 查表 → 价格 key。",
        "> 价格快照 = 2026-11-14 20:00 的 bazaar 卖价，只用于核对量级，运行时现拉。",
        "",
        "## 命名约定",
        "",
        "| 类型 | 显示名格式 | 价格 key |",
        "|---|---|---|",
        "| 普通物品 | `Revenant Flesh` | bazaar product id 或 NEU id |",
        "| 附魔书 | `Enchanted Book (Smite VI)` | `ENCHANTMENT_SMITE_6`（bazaar） |",
        "| 宠物 | `[Lvl 1] Enderman (LEGENDARY)` | `ENDERMAN;4`（LBIN） |",
        "| 符文 | `Bite Rune I` | `BITE_RUNE;1`（LBIN） |",
        "| Shard | `Paragon Shard` | `SHARD_PARAGON`（bazaar） |",
        "",
        "> 2025-08-05 起，Smite VII / Ender Slayer VII 两本书被 Endcap Upgrade 物品替换：",
        "> `SMITE;7` → `Severed Hand`（`SEVERED_HAND`），`ENDER_SLAYER;7` → `End Stone Idol`（`ENDSTONE_IDOL`）。",
        "> 书本身不再产出，只能 `/legacytrades` 兑换。",
        "",
        "## 统计",
        "",
        "| Slayer | 条目 | bazaar 可定价 | 其余（LBIN） |",
        "|---|---:|---:|---:|",
    ]

    resolved = {s: [(*resolve(i), i) for i in items] for s, items in SLAYERS.items()}

    def has_bz(key: str | None) -> bool:
        return bool(key) and key in BZ

    for slayer, rows in resolved.items():
        bz_count = sum(1 for _, key, _ in rows if has_bz(key))
        out.append(f"| {slayer} | {len(rows)} | {bz_count} | {len(rows) - bz_count} |")

    total = sum(len(r) for r in resolved.values())
    total_bz = sum(1 for rows in resolved.values() for _, key, _ in rows if has_bz(key))
    out.append(f"| **合计** | **{total}** | **{total_bz}** | **{total - total_bz}** |")
    out.append("")

    for slayer, rows in resolved.items():
        out += [f"## {slayer}", "", "| 显示名（识别键） | 价格 key | 价格源 | 快照价 |", "|---|---|---|---:|"]
        for name, bz_id, item_id in rows:
            if has_bz(bz_id):
                source, price = "BZ", BZ[bz_id]["quick_status"]["sellPrice"]
                key = bz_id
                cell = f"{price:,.0f}"
            else:
                source, price, cell = "LBIN", None, ""
                key = item_id
            out.append(f"| {name} | `{key}` | {source} | {cell} |")
        out.append("")

    out += [
        "## 备注",
        "",
        "- **shard 的显示名 ≠ 属性名**：`ATTRIBUTE_SHARD_SLAYER_DISCOUNT` 显示为 `Paragon Shard`，",
        "  bazaar key 是 `SHARD_PARAGON`；`ATTRIBUTE_SHARD_ATTACK_SPEED` 显示为 `Inferno Demonlord Shard`，",
        "  bazaar key 是 `SHARD_BURNINGSOUL`。所以必须用 NEU `attribute_shards.json` 里的",
        "  `internalName → displayName / bazaarName` 映射，不能按属性名推。",
        "- **两种新 shard**：Paragon（Slayer Discount）→ `SHARD_PARAGON`；Primordial（Cocoon Chance）→ `SHARD_PRIMORDIAL`。",
        "- **Riftstalker Bloodfiend** 在 Rift 维度，用 Motes 结算，是否纳入待定。",
        "- **LBIN 行**运行时由 `/resources/auctions/neu` 提供，key 与 NEU id 一致（含 `;稀有度`/`;等级`）。",
        "",
    ]

    (ROOT / "docs/SLAYER_TABLE.md").write_text("\n".join(out), encoding="utf-8")
    print(f"written: {total} rows, {total_bz} bazaar-priced -> docs/SLAYER_TABLE.md")


if __name__ == "__main__":
    main()
