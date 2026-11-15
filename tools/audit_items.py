"""Lists table entries that no price source can serve.

A name with a wrong internal id is the most dangerous kind of bug here: the item is dropped,
looked up, found in the table, and then priced as 0 forever. Run this after every table change.

    python tools/audit_items.py
"""
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return json.load(handle)


def main():
    items = load("src/main/resources/assets/skbprofitracker/items.json")["items"]
    bazaar = set(load("data/bz.json")["products"])
    lbin = load("data/lbin.json")

    unpriced = [(i["name"], i["key"]) for i in items
                if i["key"] not in bazaar and i["key"] not in lbin]

    print(f"{len(items)} entries, {len(items) - len(unpriced)} priced, {len(unpriced)} unpriced")
    for name, key in sorted(unpriced):
        print(f"  {name:35} {key}")

    near = [(i["name"], i["key"]) for i in items
            if i["key"] not in bazaar and i["key"] in lbin]
    print(f"\n{len(near)} entries only have an auction price:")
    for name, key in sorted(near):
        print(f"  {name:35} {key:35} {lbin[key]:,}")


if __name__ == "__main__":
    main()
