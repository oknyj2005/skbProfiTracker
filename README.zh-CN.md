# skbProfiTracker

[English](README.md) | **简体中文**

一个客户端 Fabric mod，只为回答 **Hypixel SkyBlock** 里的一个问题：

> 我现在干的这件事，每小时值多少钱？

它盯着进入背包（以及 Sacks）的东西，按 Bazaar / 拍卖行的价格估值，再除以**活跃时间** ——
所以挂机、跑图、整理背包的时候，这个数字不会虚高。

目前覆盖 **挖矿** 和 **Slayer**（含 Rift），版本 **Minecraft 26.2**。

## 下载

从 **[最新版发布页](https://github.com/oknyj2005/skbProfiTracker/releases/latest)** 下载 Mod JAR 和校验文件 `SHA256SUMS.txt`。
将 JAR 与 Fabric API、Fabric Language Kotlin 一起放进 `.minecraft/mods/`。运行环境为
Minecraft 26.2、Fabric Loader 0.18.1 或更高版本，以及 Java 25 或更高版本。

## 它是怎么算的

* **算毛产出，不是净利润。** 数值 = `Σ 数量 × 当前价`。消耗品单独记为成本，主数字从不扣它们。
* **价格永不落盘。** 只持久化数量和时长，每次重绘都按最新快照重新定价。取价顺序为
  Bazaar → 最低 BIN → NPC 收购价，并且 **NPC 价当地板**（市价低于 NPC 时按 NPC 算）。
* **活跃时间。** 三秒内出现两次强信号即可开始计时，使用挖矿消耗品也可直接开始。
  拾取会延长正在运行的计时，空闲窗口为 60 秒。Boss 战期间持续计时，手动暂停时冻结计时。
* **活动桶。** `Mining` / `Slayer` / `Rift` 由区域决定，所以在 Dwarven Mines 和 Spider's Den
  敲同一镐，会落进不同的桶。
* **切片。** 挖矿拆成 `Mining` / `Nucleus` / `Corpse`，Slayer 按类型各一个桶。尸体与 Nucleus 的
  产出从聊天战利品块读取，所以箱子里的东西不会混进普通的 `Mining` 数字。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/skbprofit gui` | 移动 / 缩放面板 |
| `/skbprofit mode [session\|total]` | 切换显示模式 |
| `/skbprofit price [offer\|insta]` | 切换价格档 |
| `/skbprofit types` | 在聊天栏列出当前活动的所有切片 |
| `/skbprofit slayer <type>` | 按 Slayer 类型过滤（`all` 复位） |
| `/skbprofit mining <type>` | 按挖矿切片过滤（`all` 复位） |
| `/skbprofit nucleustime <on\|off>` | 开关 Crystal Hollows 内的 Nucleus 计时 |
| `/skbprofit pause` | 暂停 / 继续计时 |
| `/skbprofit reset [session\|total]` | 确认后重置所选切片 |
| `/skbprofit loot [n]` | 打印聊天解析器最近读到的战利品 |
| `/skbprofit debug` | 输出活动桶 / 区域 / 价格 / 点击诊断 |

打开背包或聊天后即可操作面板。选择单个切片后，可用 `[+]`、`[-]`、`[x]` 手动修正物品数量。
下方按钮可切换模式、暂停计时，或发起当前视图的重置。

## 构建

需要 **JDK 25**（Minecraft 26.x 的 class 文件是 major version 69）。

```bash
./gradlew :26.2-fabric:build      # macOS / Linux
gradlew.bat :26.2-fabric:build    # Windows
```

产物在 `build/versions/`，丢进 `.minecraft/mods/` 即可。

## 仓库结构

```
src/main/kotlin/…        mod 源码（Fabric + Kotlin）
src/main/java/…          mixin
src/main/resources/      fabric.mod.json、mixin 配置、资源（物品表）
versions/26.2-fabric/    按版本划分的 Gradle 模块
docs/                    设计笔记与生成的物品表
tools/                   物品表生成器与抓取脚本
```

`data/`（价格与 NEU 快照）和 `reference/`（逆向时参考的第三方源码）只在本地保留，已加入 gitignore。

## 致谢

* 架构与 UI 框架派生自 **[Feesh](https://github.com/Sleepy-Panda/Feesh)**（Apache-2.0）—— 见 [`NOTICE`](NOTICE)。
* Slayer 掉落清单与 **SkyHanni** 仓库常量交叉核对。
* 显示名来自 **NotEnoughUpdates** 物品仓库。
* 价格数据：Hypixel Bazaar API 与 EliteSkyBlock 拍卖 API。

## 许可

Apache License 2.0 —— 见 [`LICENSE`](LICENSE)。
