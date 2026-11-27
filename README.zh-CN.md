# skbProfiTracker

[English](README.md) · **简体中文**

适用于 **Minecraft 26.2** 的 Hypixel SkyBlock 客户端 Fabric Mod，记录挖矿和 Slayer（含 Rift）的掉落、实际成本与活跃时间。

## 安装

从 [GitHub Releases](https://github.com/oknyj2005/skbProfiTracker/releases/latest) 下载 JAR。每次发布都附带 `SHA256SUMS.txt`，可用于核对下载文件。

将 JAR 和适用于 Minecraft 26.2 的 **Fabric API**、**Fabric Language Kotlin** 放进对应客户端的 `mods` 目录。需要 **Fabric Loader 0.18.1 或更高版本**以及 **Java 25 或更高版本**。

## 数字怎么算

- **Profit** 显示已记录的掉落和金币按当前价格折算的价值。有可估价的成本时，面板会显示 **Costs** 和 **Net profit**。在背包中悬停成本行可查看明细。
- 市价先按设定取 Bazaar 的**即时卖出**或**挂单卖出**价，再查拍卖行最低 BIN。NPC 收购价作为下限。存档记录数量、时间和实际金币数，历史掉落也按最新快照估值。
- **Session** 对应本次游戏启动，**Total** 跨启动累计。挖矿分为 Mining、Corpse 和 Nucleus。Slayer 按 Boss 类型分开，Rift 单独统计。
- 活跃计时有 **60 秒宽限**。三秒内收到两次挖掘或攻击等强信号即可开表，确认使用挖矿消耗品也能直接启动 Mining。拾取可以延长已开启的计时。确认在矮人矿区与冰洞之间换服后，会接上原本剩余的宽限。
- 想记录 Crystal Hollows 的 Nucleus 用时，可以在面板中开启，也可以运行 `/skbprofit nucleustime on`。

## 面板操作

打开背包或聊天即可切换视图、选择切片、暂停，也可以点击价格标签换价档。物品明细一次显示最多 10 行，鼠标放在列表上滚动可以翻看后续条目。

在背包里选中单个切片后，悬停物品行可用 **[+]**、**[-]**、**[x]**。修正会作用于该切片的 Session 和 Total，**[x]** 扣除行上显示的数量。**All** 汇总各切片，物品行只读。

Session 重置要在同一按钮上点两次。Total 重置需要在聊天栏确认，切换视图后仍会按最初所选的活动和切片执行。重置 Total 也会清空该目标的 Session。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/skbprofit` | 查看当前追踪状态 |
| `/skbprofit gui` | 移动、缩放或对齐面板 |
| `/skbprofit mode [session\|total]` | 切换显示视图 |
| `/skbprofit price [insta\|offer]` | 切换 Bazaar 价档 |
| `/skbprofit types` | 在聊天栏列出当前活动的切片 |
| `/skbprofit mining [slice]` | 列出或切换挖矿切片，`all` 为汇总 |
| `/skbprofit slayer [type]` | 列出或切换 Slayer 类型，`all` 为汇总 |
| `/skbprofit nucleustime [on\|off]` | 开关 Nucleus 计时 |
| `/skbprofit pause` | 暂停或继续追踪 |
| `/skbprofit reset [session\|total]` | 发起所选活动和切片的重置 |
| `/skbprofit costs` | 在聊天栏查看成本明细 |
| `/skbprofit loot [n]` | 查看最近的聊天战利品记录 |
| `/skbprofit debug` | 开关诊断并显示追踪状态和已加载版本 |

## 0.1.11 更新摘要

- Bazaar 交付和延迟上报的手动存袋按件数从挖矿利润中抵扣。
- Sack 压缩会核对原有库存，Fossil Excavator 的结算奖励从挖矿利润中排除。
- Slayer 按净金币流计算成本，Crystal Hollows 的普通挖矿需要手动开始。

完整变更见[更新日志](CHANGELOG.md)。

## 构建与测试

使用 **JDK 25** 构建。

```bash
./gradlew :26.2-fabric:build
bash tools/sim/run.sh
```

Windows 下可在 PowerShell 执行 `.\gradlew.bat :26.2-fabric:build`，离线检查用 Git Bash 运行。JAR 产物位于 `build/versions/`。离线检查覆盖物品采集、消耗品确认、冰洞往返计时和面板操作。

开发资料见[交接文档](docs/HANDOVER.md)、[采集流程](docs/CAPTURE_PIPELINE.md)和 [Slayer 掉落表](docs/SLAYER_TABLE.md)。

## 致谢与许可

架构与面板框架派生自 [Feesh](https://github.com/Sleepy-Panda/Feesh)。掉落与物品资料参考 SkyHanni 和 NotEnoughUpdates。市价来自 Hypixel Bazaar API 与 EliteSkyBlock 拍卖 API。

采用 [Apache License 2.0](LICENSE)，归属说明见 [NOTICE](NOTICE)。
