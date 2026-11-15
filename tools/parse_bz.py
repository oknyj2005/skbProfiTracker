"""解析 data/bz.json，输出按关键字过滤的 bazaar 价格表。

用法：
    python tools/parse_bz.py [关键字...]      # 不传关键字 = 打印全部
默认读取 <脚本目录>/../data/bz.json
"""
import json
import sys
from pathlib import Path

DEFAULT_KEYS = (
    "GEM", "RUBY", "SAPPHIRE", "AMBER", "AMETHYST", "JADE", "TOPAZ", "JASPER", "OPAL",
    "PERIDOT", "CITRINE", "AQUAMARINE", "ONYX",
    "MITHRIL", "TITANIUM", "GLACITE", "UMBER", "TUNGSTEN", "SORROW", "HARD_STONE",
    "SLUDGE", "TREASURITE", "YOG", "GOBLIN", "AUTOMATON", "ROBOTRON", "SUPERLITE",
    "CONTROL_SWITCH", "ELECTRON", "FTX", "SYNTHETIC", "DIVAN", "CORPSE", "JEWEL",
    "PRECURSOR", "REFINED", "SHARD_", "KEY", "FOSSIL", "PLATE", "SCUTE", "CORLEONITE",
)


def main() -> None:
    bz = Path(__file__).resolve().parent.parent / "data" / "bz.json"
    keys = tuple(sys.argv[1:]) or DEFAULT_KEYS
    products = json.loads(bz.read_text(encoding="utf-8"))["products"]

    rows = []
    for pid, v in products.items():
        if not any(k in pid for k in keys):
            continue
        q = v["quick_status"]
        sell = v["sell_summary"][0]["pricePerUnit"] if v.get("sell_summary") else 0.0
        buy = v["buy_summary"][0]["pricePerUnit"] if v.get("buy_summary") else 0.0
        rows.append((pid, sell, buy, q["sellPrice"], q["buyPrice"]))

    rows.sort()
    print(f"{'product_id':36s} {'insta_sell':>14s} {'sell_offer':>14s} {'q_sell':>14s} {'q_buy':>14s}")
    for pid, sell, buy, qs, qb in rows:
        print(f"{pid:36s} {sell:14,.1f} {buy:14,.1f} {qs:14,.1f} {qb:14,.1f}")
    print("count:", len(rows))


if __name__ == "__main__":
    main()
