# 交接文档 · skbProfiTracker

> 写于 2026-11-20。给「换机器 / 换会话 / 换人」之后接手用。
> 先读这份，再读 `docs/DESIGN.md`（为什么这么设计）和 `docs/CAPTURE_PIPELINE.md`（采集怎么工作）。

---

## 1. 这是什么

Hypixel SkyBlock 的**产出价值统计** mod（Fabric，MC 26.2，纯客户端），只覆盖两块活动：

- **Mining**（Dwarven Mines / Crystal Hollows / Glacite Tunnels / Mineshaft，含 Corpse、Nucleus 两个切片）
- **Slayer**（Revenant / Tarantula / Sven / Voidgloom / Inferno，含 Rift 吸血鬼）

核心口径（**不要改，改了所有数字都要重算**）：

- 统计的是**毛产出价值**：捡到/掉到的物品按当前市价折算，**不扣任何隐形成本**（只扣显式消耗品）。
- 价格链 = `bazaar → 拍卖 LBIN → NPC 地板`（`max(市场价, NPC 价)`），默认价档 `INSTA_SELL`。
- **只持久化数量与秒数，价格永远现算**（换价档立刻全量重算）。
- 分母 = **活跃秒**（强信号 + 宽限；Slayer/Mining 60s），不是墙钟。

---

## 2. 发布

当前版本见 [GitHub Releases](https://github.com/oknyj2005/skbProfiTracker/releases/latest)，变更记录见 [CHANGELOG.md](../CHANGELOG.md)。

收到发版指令后，更新 `gradle.properties` 中的 `mod.version` 和 `CHANGELOG.md`。构建成功且离线模拟检查通过后，提交变更、创建对应的 `v` 开头标签，并推送 `main` 和标签。GitHub Actions 会构建并发布 JAR 和 `SHA256SUMS.txt`。

---

## 3. 环境（换机器必须照做）

| 需求 | 说明 |
|---|---|
| JDK 25 | MC 26.2 本体要求。`<JDK25 安装目录>`，写进 `~/.gradle/gradle.properties` 的 `org.gradle.java.home` |
| Gradle | `gradle/wrapper/gradle-wrapper.properties` 指向 **9.5.1**（9.6 下载太慢） |
| 调用方式 | MSYS Bash 下 `./gradlew` 会坏（路径转换），统一 `java -jar gradle/wrapper/gradle-wrapper.jar …` |
| 代理 | `http://127.0.0.1:7890`（FlClash）。git 用 `-c http.proxy=…`；节点会抽风，失败就换节点重试 |
| git | 用 `"/c/Program Files/Git/cmd/git.exe"`，不要用自带的 |
| 客户端 | 任意 PCL / 官方启动器的版本目录；下文用 `<客户端版本目录>` 指代 |

**构建**：

```bash
cd <仓库目录>
java -jar gradle/wrapper/gradle-wrapper.jar :26.2-fabric:build --console=plain --no-daemon -q
```

Windows 上若 Gradle 报 `Unable to establish loopback connection`，可用 JVM 参数 `-Djdk.net.unixdomain.tmpdir` 将套接字临时目录指向一个已存在的短路径，再重试构建。

**部署**（唯一正确姿势）：

```bash
bash tools/deploy.sh          # 等客户端退出 → 跑 sim → 装进 mods → 校验
WAIT_SECONDS=7200 bash tools/deploy.sh &   # 后台排队
```

`deploy.sh` 运行 `tools/sim/run.sh`，确认产物比源码新，并在拷贝后打印条目数和 SHA-256。

**绝对不要**在游戏运行时手工 `cp` jar —— Fabric 持有 zip 句柄，覆盖后类加载失败，会掉线（已踩过）。

运行 `bash tools/sim/run.sh` 检查采集规则和在途记账。

---

## 4. 代码结构

```
src/main/kotlin/com/skbprofitracker/
├── SkbMod.kt                  # 入口，只有 init 顺序
├── capture/                   # 纯逻辑，无 MC 依赖，可离线测
│   ├── ScanRules.kt           # 判定表：增量/减量该怎么处理
│   └── CaptureBuffer.kt       # 在途状态：挂起增量、移除信用、借出返还、压缩池
├── tracker/
│   ├── ActivityBucket.kt      # 什么时候算「在干活」、当前是哪个桶/切片
│   ├── BucketLedger.kt        # 账本：items/costs/coins/elapsed/bosses/corpses + 视图
│   ├── CostLedger.kt          # 成本：消耗品价表、nucleus 挂账、硬币
│   ├── ActivitySignals.kt     # 合并挖掘回调，处理启动信号窗口
│   └── ProfitOverlay.kt       # HUD：行、按钮、切片选择器、行内编辑
├── events/publishers/
│   ├── InventoryItemPickupPublisher.kt   # 背包差分（200ms）
│   ├── SacksItemPickupPublisher.kt       # [Sacks] 聊天
│   ├── CorpseLootPublisher.kt            # 尸体战利品块
│   ├── NucleusLootPublisher.kt           # Nucleus 战利品块
│   ├── EssenceWatcher.kt                 # action bar 的 +N Essence
│   └── PurseWatcher.kt                   # 硬币、召唤费、宝物箱信号
├── items/ItemTable.kt         # 显示名 → 价格 key 的唯一关卡
├── price/PriceCache.kt        # bazaar 5min / LBIN 2min
└── utils/                     # 从 Feesh 继承的 GUI/聊天/物品/存档工具
```

**数据流**：三个入口 → `ItemTable` 查名字（查不到就丢，`[scan] unknown item`）→ 桶归属 → `ScanRules` 七道闸 → `BucketLedger.add` → `data.json`。

背包和材料袋通过 `capture/NonLootDeliveries.kt` 共用实收数量，排除 Bazaar 领取和 Fossil Excavator 结算的物品。Bazaar 下单或成交提醒不产生实收记录。

---

## 5. 采集规则（改之前必读）

`docs/CAPTURE_PIPELINE.md` 有完整表；这里记**最容易改坏**的几条：

| 机制 | 干什么 | 改坏的后果 |
|---|---|---|
| 总数法（只存 key→数量） | 不看槽位，只看每个 key 的总数变化 | 换槽/自动整理不会误判 |
| 200ms 扫描 + 界面过渡重建基线 | 界面开/关那一拍只更新基线不记账 | 不重建会在关背包瞬间记一批假收益 |
| 容器抵消 | 先用容器减少量解释上一轮挂起增量，余量才可抵消本轮增量 | 同一份减少量可能抵消两笔 |
| 挂起 1 秒（`PENDING_INCREASE_MS`） | 界面里的增量先挂起，等容器解释 | 锻造/菜单领取被记成产出 |
| sack 界面跳过 | 开着 sacks 界面时背包变多 → 一定是取出 | Oil Barrel 反复虚增的根源 |
| `[Sacks]` 双半边 | 一条消息可能同时有 `Added` 和 `Removed`，**先减后加** | 取出不撤销 |
| 压缩配对 | 产物 + 同消息原料减少 → 扣原料；跨消息用 10 分钟压缩池 | 自动压缩机双倍 |
| 借出返还（`noteReturn`） | `[Sacks] -N` 记「可能还回来」，后续 `+N` 先抵 | 取出再放回会记成新产出 |
| 取出即撤销（`undoRecentCredit`） | 按切片和数量抵扣 15 秒内的同 key。剩余有符号的取出量可匹配晚到的背包更新 | sack→箱子那类虚增 |
| SupercraftGuard | 合成产物 10s/35s 内不计 | 自己合成算收益 |
| 药水去重 2.5s | 头颅「预测放置」会产生两次减少 | 一瓶 Fiesta 记 5M |
| 两次强信号启动 | 3 秒内两次才开表；同一挖掘回调合并，宽限结束后重新判断 | 点一下就开表 |
| 喝挖矿药 = 显式开始 | 记成本 + 立即开始（无桶则挂起 5 分钟） | 忘了开表 |

---

## 6. 坑（按踩过的次数排序）

1. **别在游戏运行时改 `mods/` 里的 jar** —— 掉线、HUD 恒 0。只走 `deploy.sh`。
2. **别擅自动 `data.json`** —— 用户明确要求。需要改存档先提方案，等确认；改之前必须备份。
3. **`[Sacks]` 里的数字是净值**，不是原始掉落量；`Removed` 只在净减少时出现。据此判断双计很容易误判。
4. **`/sacks`、`Drill Anvil` 这类 Hypixel 自绘界面** 的槽位不在 `containerMenu` 里，容器抵消看不到，只能靠聊天。
5. **`Sack of Sacks`** 标题不含 ` Sack` 后缀，判定要用 `contains("sack")`。
6. 客户端进程名是 **`java.exe`**（不是 `javaw.exe`），判断"游戏在跑"要两个都查。
7. `tools/deploy.sh` 读版本号不能用 `ls -t`（MSYS 顺序不对），要读 `gradle.properties`。
8. 编译失败时 `deploy.sh` 会照装旧 jar → 已有"源码比 jar 新就拒绝"的闸门，别绕过。
9. Fabric 的 `/skbprofit debug` 会打 `loaded build: …`，**先看这行再判断 bug 是否复现**（用户经常跑旧进程）。
10. `ActivityBucket` 是顶层 object，常量不能放 `companion object`。
11. 26.2 的 `MultiPlayerGameMode#attack` 返回 **void**，mixin 用 `CallbackInfo` 不是 `CallbackInfoReturnable`。
12. 代理节点抽风会让 `api.github.com` 时报 404/超时，先 `curl -x 127.0.0.1:7890` 验一下再判断是不是仓库问题。

---

## 7. 数据与表

- `src/main/resources/assets/skbprofitracker/items.json` 随构建产物发布，由 `tools/gen_item_table.py` 生成。输入包括 SH Slayer 物品清单、挖矿与消耗品内联表、`data/neu_min` 的显示名与颜色，以及 `data/bz.json` 的价格键与 NPC 价。
- 新增物品的流程：改生成器里的清单 → `python tools/gen_item_table.py` → 看它打印的"非 bazaar key"是否合理 → `bash tools/sim/run.sh` → 构建 → 部署。
- 历史价格快照/NEU 仓库/SkyHanni 参考源码都在 `data/`、`reference/`（**已 gitignore，只在本地**）。换机器要重新拉，`tools/` 里的脚本都有说明。

---

## 8. 待办（按优先级）

1. **游戏内回归检查**。验证切片物品编辑、`All` 只读、Nucleus 秒表、挖矿药水成本与开表，以及重置确认的目标。
2. **Nucleus 完整跑一次**：开关打开 → 放水晶 → 开宝箱 → 关开关，看 `Nucleus` 切片的利润与时间是否合理。
3. **化石精粹精度**：打开 `/skbprofit debug`，用 Bazaar 前后余额验证单块和快速连续挖掘，并对照 `latest.log` 的 `[fossil]` 目标方块、服务器更新和动作栏时间。
4. **`RemovalBook` 重构**（可选）：现在有五套抵扣机制（移除信用 / 借出返还 / 跳过取出 / 挂起增量 / 压缩池），语义重叠，长期应该合成一个"物品借还账本"。
5. **Sacks 游戏内回归**：检查同名掉落分别进背包和 sack，以及先到和晚到的带符号取出消息。
6. **Modrinth 上架**（用户提过）：需要 project id + token。

---

## 9. 交接第一小时做什么

```bash
cd <仓库目录>
git log --oneline -12          # 查看近期变更
bash tools/sim/run.sh          # 运行离线检查
java -jar gradle/wrapper/gradle-wrapper.jar :26.2-fabric:build --console=plain --no-daemon -q
bash tools/deploy.sh           # 客户端关着会直接装
```

然后进游戏验：挖两下看 Mining 涨不涨、开一具尸体看 Corpse 涨而 Mining 不涨、喝一瓶药看 `Costs` 有没有 `-2.5M`。

**沟通约定**（用户定过的）：发版必须等明确指令；不改 `data.json`；改完能编译再提交；每次构建后 `deploy.sh`。
