# 实况魁地奇 Scala 后端

用 JDK 21 和 sbt，在本目录运行 `sbt run`。本地原型监听 `127.0.0.1:8080`，包含一场三分钟的鬼飞球比赛。Scala 以 20 Hz 推进状态，比赛重开期间记模拟帧并暂停比赛时钟。

运行 `sbt "run 8080 watch"` 可启动电脑对战。双方使用同一套 AI，并使用各自的球员卡。

每队人数由启动参数设置，原型支持 1 或 2 人，默认 1 人。例如 `sbt "run 8080 cpu random 2"`。开赛和控制继续走 `/matches/demo/start` 和 `/matches/demo/controls`。控制消息支持 `{"Pass":{"receiver":3}}` 和 `{"SwitchAthlete":{"athlete":3}}`，ID 来自当前阵容。切换时人类控制者和队友 AI 交换球员。

2 对 2 大厅可为格兰芬多选择进攻接应和防守战术，观战同样生效。`POST /matches/demo/start` 可随阵容提交 `"tactics":{"attackSupport":"Nearby","defense":"Cover"}`。进攻支持 `Nearby`（近距离接应）或 `Wide`（拉开接应），防守支持 `Pressure`（优先逼抢）或 `Cover`（优先回撤保护）。默认战术是 `Wide` 和 `Pressure`，斯莱特林使用默认战术。双方开赛时使用的战术随比赛快照保存。

对手持球时，`logic/computer/TeamDefense.scala` 统一为防守方分工。逼抢战术由一名球员压迫持球人，人数和接球点允许时再分配球员封不同的传球线，其余人保护不同区域。只有队友明显更接近持球人时才交接盯防任务。回撤保护也使用这套分配，由盯防者优先守射门线，队友保护各自区域。

每次启动分配独立的比赛 ID，并将第 0 帧和后续模拟帧立即写入本地 SQLite。数据库默认位于 `backend/data/matches.sqlite3`，可通过 `QUIDDITCH_DB_PATH` 指定路径。每帧包含完整的比赛状态和当步事件。服务重启会开新局，旧帧仍可通过历史接口查询。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/health` | 检查服务 |
| POST | `/auth/register` | 注册账号，提交 `username` 和 `password` |
| POST | `/auth/login` | 校验密码并签发会话令牌 |
| POST | `/auth/logout` | 撤销请求携带的会话令牌 |
| GET | `/matches/lobby` | 获取球员卡、默认对战设置和开赛状态 |
| POST | `/matches/demo/start` | 开赛，可提交 `teamSize`、`watch` 和首发球员 ID `athlete` |
| GET | `/matches/demo` | 获取最近模拟帧 |
| POST | `/matches/demo/controls` | 将操作排入下一模拟步 |
| GET | `/matches/demo/timeline/{tick}` | 按模拟帧编号查询历史快照和事件 |
| GET | `/matches/history` | 按启动时间倒序列出已记录的比赛 ID |
| GET | `/matches/history/{matchId}/timeline/{tick}` | 按比赛 ID 和帧号读取数据库中的快照和事件 |

比赛接口需要 `Authorization: Bearer <token>`。当前每次启动提供一场本地比赛，开赛账号拥有该场的操作权限，控制侧由后端绑定为主队。账号密码以加盐 PBKDF2 哈希存入 SQLite。会话保存在后端内存中，8 小时后或后端重启后失效。

## 原型球员

球员卡存放在 SQLite 的 `athlete_cards` 表中，每个球员 ID 对应一条 `Athlete` 记录。选卡页读取库内评分，提交开赛时后端重新读取球员卡，按首发 ID 和每队人数生成阵容。初始化使用 `src/main/resources/matches/athlete-cards.json` 补齐缺失的默认卡，已存在的卡保留库内值。开赛帧和后续时间轴保留该场使用的属性。

例如，在 SQLite 中把哈利的速度调整为 92。下一场比赛会读到新值。

```sql
UPDATE athlete_cards
SET card = json_set(card, '$.ratings.flight.speed', 92)
WHERE athlete_id = 1;
```

| ID | 球员 | 队伍 | 评分侧重 | `generated/` 中的模型 |
| --- | --- | --- | --- | --- |
| 1 | Harry Potter | 格兰芬多 | 飞行速度、创造力 | `Harry_Potter_Gryffindor_1516356299566129152.glb` |
| 2 | Draco Malfoy | 斯莱特林 | 转向、持球飞行、拦截 | `Draco_Malfoy_Slytherin_1516467168375513088.glb` |
| 3 | Ron Weasley | 格兰芬多 | 接球、短传、守门 | `Ron_Weasley_Gryffindor_1516467167167545344.glb` |
| 4 | Vincent Crabbe | 斯莱特林 | 身体对抗、平衡、护球 | `Vincent_Crabbe_Slytherin_1516467169587666944.glb` |

Godot 按球员 ID 加载从绑骨源文件导出的骑乘姿势和共用扫帚。四人的游戏网格位于 `client/assets/riders`。

本地调试使用 `home` 和 `away` 两个控制者。开赛后可向 `/matches/demo/controls` 发送下面的 JSON，设置主队球员的移动输入。

```json
{"player":"home","control":{"Fly":{"input":{"forward":1,"strafe":0,"climb":0,"sprint":false}}}}
```

首版场地方向为 X 轴攻防、Y 轴横移、Z 轴升降。移动输入一直生效，直到收到下一次 `Fly` 或比赛重开。停止时将三根轴设为零。发送 `{"player":"home","control":{"Catch":{"target":"Quaffle"}}}` 尝试接球。操作返回排入的模拟帧编号。等最新帧达到该编号后，可以查询对应历史帧的 `events`。

持球后可提交投球意图。以下示例朝 X 轴正方向全力出手。服务会归一化方向向量，`spin` 可为 `null`，或介于 −1 与 1 之间的数值。

```json
{"player":"home","control":{"Shoot":{"aim":{"direction":{"x":1,"y":0,"z":0},"power":1,"spin":null}}}}
```

射门在手动瞄准方向周围采样小幅正态角度偏差，实际偏角最多 6°。终结评分越高，基础方差越小。距离、出手方向与身体朝向的偏差、速度、平衡和临场稳定度还会改变偏差范围。球员命中来球时，鬼飞球会反弹并保持自由球状态。一次模拟步里，先撞到球员会触发 `QuaffleBlocked`，先穿过球环则记 10 分。进球或吹停后等待一秒继续比赛。游走球和飞贼已建模，首版比赛只启用鬼飞球。

时间轴用 `ContactAttempted` 记录进入判定窗口的冲撞或拉拽，用 `ContactResolved` 记录实际发生的身体接触。掉球、犯规和球路封堵分别有自己的事件。可结合每帧持球人和防守球员的 `Sprinting` 状态观察逼抢。

电脑根据持球者的位置、朝向和速度决定守门、封球环路线或逼抢。只在接触方向和相对速度适合时尝试冲撞。球出手后，电脑用真实比赛的球物理预测接下来最多两秒，结合球员自身的飞行规则判断能否赶到。能接稳的球会尝试接球，较快的球则需要实际挡在球路上。算法参考了 [Google Research Football 的球路预测](https://github.com/google-research/football/blob/master/third_party/gfootball_engine/src/onthepitch/ball.cpp) 和 [RoboCup HELIOS 的拦截表](https://github.com/helios-base/librcsc/blob/develop/rcsc/player/intercept_table.cpp)。

运行 `sbt "Test / runMain services.matches.logic.MatchChecks"` 验证接球、射门、计分和时间轴。

## 观战发现（待处理）

- **防守方有身位优势时过早回撤。** 防守球员已经占住持球者通往球门的路线，仍经常转身回去守门。后续验收时要能看到防守者保持身位，在能合法接触时主动上对抗，尝试打断进攻。
- **进攻方出手时机单一。** 电脑通常飞到固定的射门区域，减速接近静止后才出手。后续验收要覆盖防守者离开球门线路时的远射，以及保持飞行速度时的射门。

复查时使用现有电脑对战模式，通过比赛 ID 和模拟帧号对照球员位置、速度、持球状态和对抗或出手事件。
