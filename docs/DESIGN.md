# SkyBlock Profit Tracker — 设计定稿

> 记录截至 2026-11-14 已敲定的全部决策。代码尚未开始。
> 基底：Feesh（Fabric + Kotlin，MC 26.2）的骨架与 UI，删除钓鱼专用逻辑后重写识别与检测层。

## 1. 目标与范围

**目标**：在挖矿、刷 Slayer 时实时显示「这次活动每小时产出多少钱」。

**在范围内**
- 挖矿：Dwarven Mines / Crystal Hollows / Glacite Tunnels / Mineshaft 的方块、怪、宝箱、尸体、Nucleus、化石
- Slayer：6 种 slayer 的怪物掉落、`SHARD_*`、宠物

**不在范围内**（明确不做）
- 地牢、钓鱼（用 Feesh 本体）、附魔/合成/酿造类活动
- 拍卖/倒卖类「交易利润」
- 自动开始/停止统计（手动开关 + 活动自动分桶）

## 2. 技术底座

| 项 | 值 |
|---|---|
| MC 版本 | 26.2（单版本，不做多版本） |
| 加载器 | Fabric Loader 0.19.3 |
| 语言 | Kotlin（mixin 用 Java），fabric-language-kotlin 1.13.8+kotlin.2.3.0 |
| 构建 | Loom 1.17.11 + Deftu Gradle Toolkit 2.73.0，Java 25 |
| 依赖 | Gson；不用 resourcefulconfig（自己写 JSON 配置） |
| 入口 | ClientModInitializer |

## 3. 已定决策

### 3.1 收益口径 = 毛产出价值
- 只统计**获得**的物品价值，**不扣成本**（成本单独一个模块、单独显示）。
- 不统计物品的「消失」，卖出/消耗不产生负收益。
- 宠物：**与活动无关的通用机制**（挖矿同样会大量练宠，不独属 Slayer）。掉落时按 1 级计价；练到满级那一刻再记 `LBIN(max) − LBIN(1)` 的差额（两笔相加 = 满级价）。中间等级不记。归入事件发生时的当前桶。

### 3.2 定价（D2）
- 三档：`NPC_SELL` / `INSTA_SELL` / `SELL_OFFER`，**默认 `INSTA_SELL`**（与 SkyHanni 的 `BAZAAR_INSTANT_SELL` 一致），设置里可切换，切换后全量重算。
- 取价顺序：bazaar → auction LBIN → 0。
- 不扣税、不平滑（用即时价）、不做七日均价。
- 价格每 5 分钟刷新；**持久层只存数量与时长，价格永远现算**。

### 3.3 物品识别（D4）
- **按显示名识别**：显示名 → 查表 → 价格 key（照 Feesh）。
- 清洗：去颜色码、去 `§8xN` 后缀、去 ☘ 前缀；表内支持别名。
- 表外物品：**直接忽略**（不记账），需要靠勤更新表覆盖新物品。
- 不用 NBT id 当键：所有 attribute shard 共用 `ATTRIBUTE_SHARD`，会撞成一个物品。

### 3.4 时间口径（D3）
- 分母 = **活跃秒**（排除挂机/跑图/整理背包）。
- **强信号**（破坏方块、攻击实体）可以启动计时；**弱信号**（拾取、受伤）只能延长，不能启动。
- 宽限 = **180s**（与 Feesh 的 `trackersAutoPauseSeconds` 一致）。
- 自动暂停只停表；**手动暂停全停**（不计时也不记账）。
- 切世界/离开 Skyblock 全停，回来靠强信号续上，不重置。

### 3.5 双桶（自动分桶）
- 桶名：`Mining` / `Slayer`。
- **Slayer 桶开** = 计分板出现 `Slayer Quest: ...` **且**在该 slayer 对应区域。
- **Mining 桶开** = 在 Dwarven Mines / Crystal Hollows / Glacite Tunnels / Mineshaft。
- 区域门写死，保证两桶物理互斥。
- 归属规则：**按拾取时刻哪个桶开着来判**（不看物品表里的活动列）。
- 桶关闭后**延 15s** 尾巴（计时+记账都延续）；两桶都关时拾到的东西忽略。
- Session / Total 双模式，分开 reset。

### 3.6 检测层（照拷 Feesh 四件套）
1. 背包总量法：36 格 + 打开背包时并入盔甲/副手，`pickupAmount = 增量 − min(增量, 容器减少量)`
2. 界面时间窗过滤（见 3.7）
3. Sacks 聊天解析（只认 `+` 行，35s 窗口）
4. 会话门：overlay 可见且未暂停才记账

### 3.7 过滤器清单（13 条）
Bazaar 界面 2s / Bazaar 消息 2s / 拍卖界面 5s / Create Auction / NPC 购买消息 2s / NPC 买回 2s / 玩家交易 2s / Supercraft 2s / Sacks 界面（标题以 Sack 结尾）/ `/gfs` 2s / Pets 菜单 / Hunting Box / 宠物道具更换 2s。

已删 8 条（表里没有这些类别，查表处就 return）：Fishing Bag 鱼饵、Trophy Fish 切片、Trophy Frogs 切片、钓鱼宝藏 shard、Time Pocket、Accessory Bag、Kat、George。

### 3.8 欠账抵扣（解决压缩/合成双计）
1. 每 250ms 扫描算出**完整正负变化表**（不只挑增加）。
2. 减少 → `pending[itemId] += n`，10 秒有效。
3. 增加时若该物品标了 `compactsFrom` + `compactRatio`，且 `pending[parent] >= ratio × 增加量` → 扣欠账、**不计这一笔**；否则正常计。

覆盖：手动 Supercraft、自动 Compact、Pristine/附魔直掉（无减少 → 正常计）、丢出再捡、bazaar 收回。
注意：`pending` 只记录**通过过滤器之后**的减少。

### 3.9 成本模块
```
cost/
├── CostLedger.kt      // (名称, 数量, 单价, 小计, 类别)
├── CoinCostSource.kt  // purse → 召唤费、auto-slayer 银行代付
└── ItemCostSource.kt  // 扫描器「过滤后有减少」+ 白名单 → 记成本
```
- 类别：`SLAYER_SPAWN` / `SLAYER_GUMMY` / `CORPSE_KEY` / `NUCLEUS_MATERIAL`
- 召唤费照抄 SH：聊天 `SLAYER QUEST STARTED!` 触发 + 1.5s 窗口 + purse 真实差值 + `-350,000` 上限（自动兼容 Aatrox / shard / 市长折扣）
- 物品成本：过滤后的减少 + 白名单（钥匙、软糖、Nucleus 材料）
- 收益与成本完全隔离，UI 各取一份，不互相污染

### 3.10 UI
- 直接改造 Feesh 的 `FishingProfitTracker` overlay：Session/Total 双栏、逐物品行、成本区、总计行带价档角标。
- 显示阈值默认 100 万，低于折叠进 "Other items"（只影响显示）。

## 4. 模块结构

```
com.<modid>.profit
├── core/     Session, Tracker, EventBus, 活跃秒计时
├── detect/   InventoryScanner, SackParser, PurseWatcher, SlayerWatcher, MiningWatcher, Filters
├── items/    ItemTable（mining / slayer）, 显示名清洗, 别名
├── price/    PriceCache, BazaarClient, AuctionClient
├── cost/     CostLedger, CoinCostSource, ItemCostSource
├── ui/       ProfitOverlay, MoveGuis, ConfigScreen
└── data/     Config, PersistentData
```

## 5. 物品表

### 5.1 入表规则
**能直接掉落 + bazaar 有价 → 进表。** 锻造/合成只影响「怎么获得」，不影响「算不算收益」。

### 5.2 挖矿表 v2（约 110 条，48 条批量生成）

| 组 | 内容 | 备注 |
|---|---|---|
| A 方块 | Mithril / Titanium / Glacite / Umber / Tungsten / Hard Stone + 附魔形态 | 压缩比 160/160/160/160/160/**576** |
| B 宝石 | 12 种 × Rough/Flawed/Fine/Flawless = 48 | Perfect 不进表；合成比 80/80/80/5 |
| C 怪物&宝箱 | Sorrow、Volta、Sludge Juice、Yoggie、Goblin Egg ×4、Treasurite、Glacite Jewel、机器人零件 ×6、Corleonite、`SHARD_*` | |
| D 尸体 | Lapis / Umber / Tungsten / Vanguard | 见 5.3 |
| E Nucleus | 6 种 Fine、稀有 Flawless、Divan's Alloy、Divan Fragment、Jaderald、Quick Claw、Pickonimbus、Bob-omb、Prehistoric Egg、Claw Fossil、Oil Barrel、Treasurite、Gemstone Mixture | |
| F 事件 | Tasty Mithril、Starfall、Glossy Gemstone、Suspicious Scrap | |
| F.5 化石 | Spine / Tusk / Webbed / Ugly / Clubbed / Footprint / Essence | 来自 Fossil Excavator |

**不进表**：Perfect 宝石、Precursor Apparatus、Dwarven Geode（原 Rock Gemstone）、Secret Railroad Pass、Mangrove Gem、Moonglade Jewel、Divan's Powder Coating；以及无 bazaar 价的 Caged Wisp / Shattered Locket / Frostbitten Dye / Helix Fossil / Gemstone Crystals。

### 5.3 尸体掉落
| 尸体 | 钥匙 | 特有 |
|---|---|---|
| Lapis | 免费 | — |
| Umber | Umber Key | 与 Tungsten 共用表 |
| Tungsten | Tungsten Key | 同上 |
| Vanguard | Skeleton Key | Frozen Scute、Plate ×3、Refined ×2、Glacite Amalgamation、Dwarven O's Metallic Minis |

共用：Flawed/Fine 四种宝石、Enchanted Glacite/Umber/Tungsten、Glacite Jewel、Suspicious Scrap、Goblin Egg ×3、Bejeweled Handle、三种 Key（**钥匙自己也会从尸体掉出来**，走收益侧）。

### 5.4 Slayer 表（已生成）
完整表见 [`SLAYER_TABLE.md`](SLAYER_TABLE.md)，由 `tools/gen_slayer_table.py` 从
SkyHanni 物品清单 + NEU 显示名 + bazaar 快照生成。

- 6 种 slayer，共 **153 条**：bazaar 可定价 79 条，其余 74 条走拍卖 LBIN
- 物品清单只取「有哪些物品」，识别/定价仍按 Feesh：显示名 → 查表 → 价格 key
- 命名约定：`Enchanted Book (Smite VI)` / `[Lvl 1] Enderman (LEGENDARY)` / `Bite Rune I` / `Paragon Shard`
- **shard 的显示名 ≠ 属性名**：`ATTRIBUTE_SHARD_SLAYER_DISCOUNT` → `Paragon Shard` / `SHARD_PARAGON`；
  `ATTRIBUTE_SHARD_ATTACK_SPEED` → `Inferno Demonlord Shard` / `SHARD_BURNINGSOUL`。
  映射表取自 NEU `attribute_shards.json`，不能按名字推。
- 两种新 shard：Paragon（Slayer Discount）、Primordial（Cocoon Chance）
- **附魔书走 bazaar**：价格 key = `ENCHANTMENT_<附魔>_<等级>`（`ENCHANTMENT_SMITE_6`），
  不是 NEU 那套 `SMITE;6` —— 之前生成表时漏了这层映射，书全部落到 LBIN 查不到
- **2025-08-05 起 VII 书被 Endcap Upgrade 物品替换**：`SMITE;7` → `Severed Hand`（`SEVERED_HAND`，7.0M）、
  `ENDER_SLAYER;7` → `End Stone Idol`（`ENDSTONE_IDOL`，37.1M）；书本身不再产出，只能 `/legacytrades`
- **Rift 纳入，仍按金币计**。判定规则统一为：**有金币价 = 能带出去**，无价 = Rift 内部物品，不计。
  - Vampire Slayer 12 条掉落里 bz 有价 4 条：Guardian Lucky Block 3,132,032 / Bubba Blister 390,004 /
    Fang-tastic Chocolate Chip 232,696 / Hemovibe 167；其余 8 条（Coven Seal、McGrubber's Burger、
    Unfanged Vampire Part、Vampiric Melon、Soultwist Rune I、两种 Book Bundle、Sangria Dye）无金币价。
  - 表里保留这 8 条但标 `price: none`，运行时价格 0 → 不生成行；将来有价自动生效。
  - 四件 Rift 专属处理：
    1. 区域门 = 计分板 `The Rift` / `Stillgore Château`，与 Mining / Slayer 并列
    2. **进出维度必须重置背包基线**（进去整包换掉，不重置会记一笔巨额假收益）
    3. 召唤费是 Motes，不进金币成本账本 → Rift 桶不显示成本行
    4. Rift 里计分板显示 `Motes:` 而非 `Purse:`，金币解析天然不匹配，**不要为此放宽正则**

### 5.5 宠物（通用模块，不属任何桶）
- **计入时机 1：掉落**（Scatha、Bal、Slayer 系宠物）→ 按 1 级价计，归当前桶。
- **计入时机 2：满级** → 聊天 `§aYour <pet> §aleveled up to level 100§a!` 触发，记 `LBIN(max) − LBIN(1)`，归当前桶。
  - 上限 200 的宠物：Golden Dragon / Rose Dragon / Jade Dragon（Ender Dragon 仍是 100）
  - 只认满级那一帧，中间等级不记（中间等级没有可靠挂单价）
  - 玩家关掉该聊天消息 = 漏记，已知代价，照 Feesh
- 价格 key：`<PET_ID>;<rarity>`（NEU id + 稀有度数值），LBIN 走拍卖 API
- 练级可能在 Mining 桶里发生（挖矿给大量宠物经验），所以不放进 Slayer 表

## 6. 已知陷阱（Feesh 的坑，必须避开）

1. `InventoryItemPickupPublisher.kt:207` 差分只处理增加，减少被无视 → 我们要正负全算
2. `:219-220` 无条件写回基线——"跳过"也必须更新基线，否则下一 5 tick 重复计
3. `GuiUtils.kt:122-125 isInSacksGui` 用 `title.endsWith("Sack")`，主菜单标题是 "Sacks"，漏
4. 35s sack 窗口会误杀真实掉落
5. Sacks 只解析 `+` 行，忽略 `-` 行（那本是"取出/消耗"的现成信号）
6. 合成只拦 Supercraft，普通合成/自动 Compact 挡不住 → 用欠账机制
7. `ignoreItemPickup` 表字段从未被读取
8. 记账被 overlay 可见性门控（`:536`），坑用户
9. `isMaxedPet` 只认真满级宠，与我们的口径不同

## 7. 参考源码

| 路径 | 内容 |
|---|---|
| `reference/feesh_src/Feesh-develop/` | Feesh 全量源码（主要参考） |
| `reference/skyhanni/` | SkyHanni 相关文件（PurseApi / SackApi / ItemAddManager / SlayerProfitTracker / TrackerData / OwnInventoryData） |
| `reference/wiki/` | 已抓取的 wiki 文本（Pristine、Frozen Corpses、宝石合成等） |
| `data/bz.json` | bazaar 全量快照（2026-11-14 20:00） |
| `tools/parse_bz.py` | 解析 bz.json 出价格表 |

## 8. 未决事项

- [ ] Slayer 表内容
- [ ] UI 具体行布局（现有 Feesh 行够不够）
- [ ] 命令（reset / pause / 手动开关）
- [ ] 配置项清单
- [ ] 钓鱼时自动暂停本 mod（避免和 Feesh 双计）

## 聊天撤离：尸体 / Nucleus / Sacks 的记账口径

- 尸体与 Nucleus 的奖励**只从聊天块记账**（`  LAPIS CORPSE LOOT!` / `  CRYSTAL NUCLEUS LOOT BUNDLE` → 4 空格缩进的奖励行 → 64 个 `▬` 的结束行），因为按物品身份区分必然误判（Flawed 宝石、Enchanted Glacite/Umber/Tungsten 挖矿也会掉）。
- 聊天先记 → 背包再捡到同一件时不能重复：`BucketLedger` 用「信用额度」抵扣，背包若抢在聊天之前被扫到，则从 Mining 里回撤（5 秒窗口）。
- Sacks 消息（`[Sacks] +N`）走同一条 `BucketLedger.add`，因此也会消耗信用额度；它以前直接写进基础桶，导致「矿袋里的矿在 Mining 切片里看不见」。
- 启动时把旧的「基础 mining 桶」合并进 `Mining` 切片（ALL 前后总额不变）。
- `/skbprofit loot [n]` 打印最近 n 条聊天战利品（时间、切片、数量、名字、key）；名字查不到表时 key 显示 `unknown`，这是补表的直接线索。
