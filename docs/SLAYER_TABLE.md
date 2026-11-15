# Slayer 掉落表

> 物品清单取自 SkyHanni `constants/SlayerProfitTrackerItems.json`，**仅取「有哪些物品」**；
> 识别与定价按 Feesh 的做法：显示名 → 查表 → 价格 key。
> 价格快照 = 2026-11-14 20:00 的 bazaar 卖价，只用于核对量级，运行时现拉。

## 命名约定

| 类型 | 显示名格式 | 价格 key |
|---|---|---|
| 普通物品 | `Revenant Flesh` | bazaar product id 或 NEU id |
| 附魔书 | `Enchanted Book (Smite VI)` | `ENCHANTMENT_SMITE_6`（bazaar） |
| 宠物 | `[Lvl 1] Enderman (LEGENDARY)` | `ENDERMAN;4`（LBIN） |
| 符文 | `Bite Rune I` | `BITE_RUNE;1`（LBIN） |
| Shard | `Paragon Shard` | `SHARD_PARAGON`（bazaar） |

> 2025-08-05 起，Smite VII / Ender Slayer VII 两本书被 Endcap Upgrade 物品替换：
> `SMITE;7` → `Severed Hand`（`SEVERED_HAND`），`ENDER_SLAYER;7` → `End Stone Idol`（`ENDSTONE_IDOL`）。
> 书本身不再产出，只能 `/legacytrades` 兑换。

## 统计

| Slayer | 条目 | bazaar 可定价 | 其余（LBIN） |
|---|---:|---:|---:|
| Revenant Horror | 27 | 18 | 9 |
| Tarantula Broodfather | 29 | 19 | 10 |
| Sven Packmaster | 20 | 9 | 11 |
| Voidgloom Seraph | 32 | 16 | 16 |
| Inferno Demonlord | 33 | 23 | 10 |
| Riftstalker Bloodfiend | 12 | 4 | 8 |
| **合计** | **153** | **89** | **64** |

## Revenant Horror

| 显示名（识别键） | 价格 key | 价格源 | 快照价 |
|---|---|---|---:|
| Revenant Flesh | `REVENANT_FLESH` | BZ | 17 |
| Foul Flesh | `FOUL_FLESH` | BZ | 24,006 |
| Zombie Slayer Rune I | `ZOMBIE_SLAYER_RUNE;1` | LBIN |  |
| Undead Catalyst | `UNDEAD_CATALYST` | BZ | 7 |
| Enchanted Book (Smite VI) | `ENCHANTMENT_SMITE_6` | BZ | 6 |
| Beheaded Horror | `BEHEADED_HORROR` | LBIN |  |
| Revenant Catalyst | `REVENANT_CATALYST` | BZ | 8,000 |
| Snake Rune I | `SNAKE_RUNE;1` | LBIN |  |
| Revenant Viscera | `REVENANT_VISCERA` | BZ | 38,009 |
| Scythe Blade | `SCYTHE_BLADE` | LBIN |  |
| Severed Hand | `SEVERED_HAND` | BZ | 7,000,014 |
| Shredded Sinew | `SHARD_OF_THE_SHREDDED` | LBIN |  |
| Warden Heart | `WARDEN_HEART` | LBIN |  |
| Rotten Flesh | `ROTTEN_FLESH` | BZ | 1 |
| Gold Ingot | `GOLD_INGOT` | BZ | 1 |
| Golden Powder | `GOLDEN_POWDER` | BZ | 26,272 |
| [Lvl 1] Ghoul (EPIC) | `GHOUL;3` | LBIN |  |
| [Lvl 1] Ghoul (LEGENDARY) | `GHOUL;4` | LBIN |  |
| Matcha Dye | `DYE_MATCHA` | LBIN |  |
| Revenant Shard | `SHARD_REVENANT` | BZ | 10,001 |
| Sycophant Shard | `SHARD_SYCOPHANT` | BZ | 36,780 |
| Golden Ghoul Shard | `SHARD_GOLDEN_GHOUL` | BZ | 10,016 |
| Lapis Zombie Shard | `SHARD_LAPIS_ZOMBIE` | BZ | 4,167 |
| Miner Zombie Shard | `SHARD_MINER_ZOMBIE` | BZ | 5,677 |
| Paragon Shard | `SHARD_PARAGON` | BZ | 1,650,090 |
| Festering Maggot | `FESTERING_MAGGOT` | BZ | 295,220 |
| Severed Hand | `SEVERED_HAND` | BZ | 7,000,014 |

## Tarantula Broodfather

| 显示名（识别键） | 价格 key | 价格源 | 快照价 |
|---|---|---|---:|
| Tarantula Web | `TARANTULA_WEB` | BZ | 849 |
| Toxic Arrow Poison | `TOXIC_ARROW_POISON` | BZ | 2,351 |
| Bite Rune I | `BITE_RUNE;1` | LBIN |  |
| Spider Catalyst | `SPIDER_CATALYST` | BZ | 20,070 |
| Enchanted Book (Bane of Arthropods VI) | `ENCHANTMENT_BANE_OF_ARTHROPODS_6` | BZ | 0 |
| Fly Swatter | `FLY_SWATTER` | BZ | 476,598 |
| Tarantula Talisman | `TARANTULA_TALISMAN` | LBIN |  |
| Digested Mosquito | `DIGESTED_MOSQUITO` | LBIN |  |
| Spider Eye | `SPIDER_EYE` | BZ | 0 |
| String | `STRING` | BZ | 3 |
| Bone | `BONE` | BZ | 0 |
| [Lvl 1] Tarantula (EPIC) | `TARANTULA;3` | LBIN |  |
| [Lvl 1] Tarantula (LEGENDARY) | `TARANTULA;4` | LBIN |  |
| Travel Scroll to Spider's Den Top of Nest | `SPIDERS_DEN_TOP_TRAVEL_SCROLL` | LBIN |  |
| Arachne's Calling | `ARACHNE_KEEPER_FRAGMENT` | BZ | 9 |
| Burning Eye | `BURNING_EYE` | BZ | 682 |
| Darkness Within Rune I | `DARKNESS_WITHIN_RUNE;1` | LBIN |  |
| Tarantula Catalyst | `TARANTULA_CATALYST` | BZ | 81,251 |
| Vial of Venom | `VIAL_OF_VENOM` | BZ | 430,994 |
| Primordial Eye | `PRIMORDIAL_EYE` | LBIN |  |
| Ensnared Snail | `ENSNARED_SNAIL` | BZ | 1,850,032 |
| Shriveled Wasp | `SHRIVELED_WASP` | LBIN |  |
| Tarantula Silk | `TARANTULA_SILK` | BZ | 184,829 |
| Flaming Spider Shard | `SHARD_FLAMING_SPIDER` | BZ | 10,025 |
| Voracious Spider Shard | `SHARD_VORACIOUS_SPIDER` | BZ | 3,946 |
| Arachne Shard | `SHARD_ARACHNE` | BZ | 37,553 |
| Primordial Shard | `SHARD_PRIMORDIAL` | BZ | 806,064 |
| Paragon Shard | `SHARD_PARAGON` | BZ | 1,650,090 |
| Brick Red Dye | `DYE_BRICK_RED` | LBIN |  |

## Sven Packmaster

| 显示名（识别键） | 价格 key | 价格源 | 快照价 |
|---|---|---|---:|
| Wolf Tooth | `WOLF_TOOTH` | BZ | 432 |
| Hamster Wheel | `HAMSTER_WHEEL` | BZ | 19,106 |
| Spirit Rune I | `SPIRIT_RUNE;1` | LBIN |  |
| Enchanted Book (Critical VI) | `ENCHANTMENT_CRITICAL_6` | BZ | 0 |
| Furball | `FURBALL` | BZ | 0 |
| Red Claw Egg | `RED_CLAW_EGG` | LBIN |  |
| Couture Rune I | `COUTURE_RUNE;1` | LBIN |  |
| Overflux Capacitor | `OVERFLUX_CAPACITOR` | LBIN |  |
| Grizzly Salmon | `GRIZZLY_BAIT` | LBIN |  |
| Bone | `BONE` | BZ | 0 |
| Wolf Talisman | `WOLF_TALISMAN` | LBIN |  |
| Travel Scroll to Howling Cave | `PARK_CAVE_TRAVEL_SCROLL` | LBIN |  |
| [Lvl 1] Hound (EPIC) | `HOUND;3` | LBIN |  |
| [Lvl 1] Hound (LEGENDARY) | `HOUND;4` | LBIN |  |
| Weak Wolf Catalyst | `WEAK_WOLF_CATALYST` | BZ | 55,001 |
| Foraging Exp Boost | `PET_ITEM_FORAGING_SKILL_BOOST_EPIC` | LBIN |  |
| Celeste Dye | `DYE_CELESTE` | LBIN |  |
| Soul of the Alpha Shard | `SHARD_SOUL_OF_THE_ALPHA` | BZ | 16,463 |
| Paragon Shard | `SHARD_PARAGON` | BZ | 1,650,090 |
| Howling Spirit Shard | `SHARD_HOWLING_SPIRIT` | BZ | 1,015 |

## Voidgloom Seraph

| 显示名（识别键） | 价格 key | 价格源 | 快照价 |
|---|---|---|---:|
| Null Sphere | `NULL_SPHERE` | BZ | 90 |
| Twilight Arrow Poison | `TWILIGHT_ARROW_POISON` | BZ | 576 |
| Endersnake Rune I | `ENDERSNAKE_RUNE;1` | LBIN |  |
| Summoning Eye | `SUMMONING_EYE` | BZ | 1,206,677 |
| Enchanted Book (Mana Steal I) | `ENCHANTMENT_MANA_STEAL_1` | BZ | 141 |
| Transmission Tuner | `TRANSMISSION_TUNER` | BZ | 29,422 |
| Null Atom | `NULL_ATOM` | BZ | 31,043 |
| Hazmat Enderman | `HAZMAT_ENDERMAN` | BZ | 6,094 |
| Pocket Espresso Machine | `POCKET_ESPRESSO_MACHINE` | LBIN |  |
| Enchanted Book (Smarty Pants I) | `ENCHANTMENT_SMARTY_PANTS_1` | BZ | 90,472 |
| Dragon Rune I | `DRAGON_RUNE;1` | LBIN |  |
| Handy Blood Chalice | `HANDY_BLOOD_CHALICE` | LBIN |  |
| Sinful Dice | `SINFUL_DICE` | LBIN |  |
| Exceedingly Rare Ender Artifact Upgrade | `EXCEEDINGLY_RARE_ENDER_ARTIFACT_UPGRADER` | LBIN |  |
| Void Conqueror Enderman Skin | `PET_SKIN_ENDERMAN_SLAYER` | LBIN |  |
| Etherwarp Merger | `ETHERWARP_MERGER` | LBIN |  |
| Judgement Core | `JUDGEMENT_CORE` | LBIN |  |
| Enchant Rune I | `ENCHANT_RUNE;1` | LBIN |  |
| End Stone Idol | `ENDSTONE_IDOL` | BZ | 37,098,175 |
| Enchanted Ender Pearl | `ENCHANTED_ENDER_PEARL` | BZ | 284 |
| Ender Pearl | `ENDER_PEARL` | BZ | 4 |
| Enderman Cortex Rewriter | `ENDERMAN_CORTEX_REWRITER` | BZ | 60,538 |
| [Lvl 1] Enderman (COMMON) | `ENDERMAN;0` | LBIN |  |
| [Lvl 1] Enderman (UNCOMMON) | `ENDERMAN;1` | LBIN |  |
| [Lvl 1] Enderman (RARE) | `ENDERMAN;2` | LBIN |  |
| [Lvl 1] Enderman (EPIC) | `ENDERMAN;3` | LBIN |  |
| [Lvl 1] Enderman (LEGENDARY) | `ENDERMAN;4` | LBIN |  |
| Byzantium Dye | `DYE_BYZANTIUM` | LBIN |  |
| Zealot Bruiser Shard | `SHARD_BRUISER` | BZ | 3,081 |
| Zealot Shard | `SHARD_ZEALOT` | BZ | 3,000 |
| Paragon Shard | `SHARD_PARAGON` | BZ | 1,650,090 |
| End Stone Idol | `ENDSTONE_IDOL` | BZ | 37,098,175 |

## Inferno Demonlord

| 显示名（识别键） | 价格 key | 价格源 | 快照价 |
|---|---|---|---:|
| Derelict Ashe | `DERELICT_ASHE` | BZ | 1,413 |
| Lavatears Rune I | `LAVATEARS_RUNE;1` | LBIN |  |
| Wisp Potion | `WISP_POTION` | LBIN |  |
| Bundle of Magma Arrows | `ARROW_BUNDLE_MAGMA` | BZ | 51,864 |
| Mana Disintegrator | `MANA_DISINTEGRATOR` | BZ | 1,103 |
| Scorched Books | `SCORCHED_BOOKS` | BZ | 4,381 |
| Kelvin Inverter | `KELVIN_INVERTER` | BZ | 91,204 |
| Blaze Rod Distillate | `BLAZE_ROD_DISTILLATE` | BZ | 1,053 |
| Glowstone Distillate | `GLOWSTONE_DUST_DISTILLATE` | BZ | 5,000 |
| Magma Cream Distillate | `MAGMA_CREAM_DISTILLATE` | BZ | 5,005 |
| Nether Wart Distillate | `NETHER_STALK_DISTILLATE` | BZ | 4,545 |
| Gabagool Distillate | `CRUDE_GABAGOOL_DISTILLATE` | BZ | 20,193 |
| Scorched Power Crystal | `SCORCHED_POWER_CRYSTAL` | BZ | 462,337 |
| Archfiend Dice | `ARCHFIEND_DICE` | LBIN |  |
| Enchanted Book (Fire Aspect III) | `ENCHANTMENT_FIRE_ASPECT_3` | BZ | 7,000 |
| Fiery Burst Rune I | `FIERY_BURST_RUNE;1` | LBIN |  |
| Flawed Opal Gemstone | `FLAWED_OPAL_GEM` | BZ | 216 |
| Enchanted Book (Duplex I) | `ENCHANTMENT_ULTIMATE_REITERATE_1` | BZ | 581,648 |
| High Class Archfiend Dice | `HIGH_CLASS_ARCHFIEND_DICE` | LBIN |  |
| Wilson's Engineering Plans | `WILSON_ENGINEERING_PLANS` | LBIN |  |
| Subzero Inverter | `SUBZERO_INVERTER` | BZ | 1,100,001 |
| Blaze Ashes | `BLAZE_ASHES` | BZ | 4 |
| Blaze Rod | `BLAZE_ROD` | BZ | 5 |
| Enchanted Blaze Powder | `ENCHANTED_BLAZE_POWDER` | BZ | 1,729 |
| Netherrack-Looking Sunshade | `NETHERRACK_LOOKING_SUNSHADE` | LBIN |  |
| Millennia-Old Blaze Ashes | `MILLENIA_OLD_BLAZE_ASHES` | BZ | 2,600 |
| Enchanted Book (Smoldering I) | `ENCHANTMENT_SMOLDERING_1` | BZ | 2,733,840 |
| Sword of Bad Health | `SWORD_OF_BAD_HEALTH` | LBIN |  |
| Flame Dye | `DYE_FLAME` | LBIN |  |
| Inferno Demonlord Shard | `SHARD_BURNINGSOUL` | BZ | 96,162 |
| Paragon Shard | `SHARD_PARAGON` | BZ | 1,650,090 |
| Nether Star | `CORRUPTED_NETHER_STAR` | BZ | 10,111 |
| Cyclamen Dye | `DYE_CYCLAMEN` | LBIN |  |

## Riftstalker Bloodfiend

| 显示名（识别键） | 价格 key | 价格源 | 快照价 |
|---|---|---|---:|
| Coven Seal | `COVEN_SEAL` | LBIN |  |
| Enchanted Book Bundle | `ENCHANTED_BOOK_BUNDLE_QUANTUM` | LBIN |  |
| Soultwist Rune I | `SOULTWIST_RUNE;1` | LBIN |  |
| Bubba Blister | `BUBBA_BLISTER` | BZ | 390,004 |
| Fang-tastic Chocolate Chip | `CHOCOLATE_CHIP` | BZ | 232,696 |
| Guardian Lucky Block | `GUARDIAN_LUCKY_BLOCK` | BZ | 3,132,032 |
| McGrubber's Burger | `MCGRUBBER_BURGER` | LBIN |  |
| Unfanged Vampire Part | `UNFANGED_VAMPIRE_PART` | LBIN |  |
| Enchanted Book Bundle | `ENCHANTED_BOOK_BUNDLE_THE_ONE` | LBIN |  |
| Hemovibe | `HEMOVIBE` | BZ | 167 |
| Vampiric Melon | `VAMPIRIC_MELON` | LBIN |  |
| Sangria Dye | `DYE_SANGRIA` | LBIN |  |

## 备注

- **shard 的显示名 ≠ 属性名**：`ATTRIBUTE_SHARD_SLAYER_DISCOUNT` 显示为 `Paragon Shard`，
  bazaar key 是 `SHARD_PARAGON`；`ATTRIBUTE_SHARD_ATTACK_SPEED` 显示为 `Inferno Demonlord Shard`，
  bazaar key 是 `SHARD_BURNINGSOUL`。所以必须用 NEU `attribute_shards.json` 里的
  `internalName → displayName / bazaarName` 映射，不能按属性名推。
- **两种新 shard**：Paragon（Slayer Discount）→ `SHARD_PARAGON`；Primordial（Cocoon Chance）→ `SHARD_PRIMORDIAL`。
- **Riftstalker Bloodfiend** 在 Rift 维度，用 Motes 结算，是否纳入待定。
- **LBIN 行**运行时由 `/resources/auctions/neu` 提供，key 与 NEU id 一致（含 `;稀有度`/`;等级`）。
