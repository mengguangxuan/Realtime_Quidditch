# 实况魁地奇 Scala 后端

用 JDK 21 和 sbt，在本目录运行 `sbt run`。本地原型监听 `127.0.0.1:8080`，包含一场三分钟的鬼飞球比赛。Scala 以 20 Hz 推进状态，比赛重开期间记模拟帧并暂停比赛时钟。

运行 `sbt "run 8080 watch"` 可启动电脑对战。双方使用同一套 AI，并使用各自的球员卡。

每队可选 1、2 或 7 人，默认 1 人。可在大厅切换，也可用 `sbt "run 8080 cpu random 7"` 预选七人制。七人制使用格兰芬多与巴塞罗那阵容，双方争夺同一个鬼飞球。控制消息支持 `{"Pass":{"receiver":3}}` 和 `{"SwitchAthlete":{"athlete":3}}`，ID 来自当前阵容。切换时人类控制者和队友 AI 交换球员。Godot 中按 Tab 依次切换队友，持球时按 R 传给与瞄准方向最接近的队友。

2 对 2 和 7 对 7 大厅可为格兰芬多选择进攻接应和防守战术，观战同样生效。`POST /matches/demo/start` 可随阵容提交 `"tactics":{"attackSupport":"Nearby","defense":"Cover"}`。进攻支持 `Nearby`（近距离接应）或 `Wide`（拉开接应），防守支持 `Pressure`（优先逼抢）或 `Cover`（优先回撤保护）。默认战术是 `Wide` 和 `Pressure`，客队使用默认战术。双方开赛时使用的战术随比赛快照保存。

对手持球时，`logic/computer/TeamDefense.scala` 统一为防守方分工。逼抢战术由一名球员压迫持球人，人数和接球点允许时再分配球员封不同的传球线，其余人保护不同区域。只有队友明显更接近持球人时才交接盯防任务。回撤保护也使用这套分配，由盯防者优先守射门线，队友保护各自区域。

大厅右侧可在俯视图选择格兰芬多球员的开场站位，摆位和左侧的首发球员选择相互独立。`POST /matches/demo/start` 接受可选的 `startingPositions`，例如 `{"3":{"value":{"x":-21,"y":-7,"z":7}}}`。位置必须在己方可用区域，队友之间至少相距三米，高度沿用 7 米。未编辑的球员按默认阵型开场。自定义站位随比赛配置和首帧保存，进球后的重开沿用比赛现有规则。

每次启动分配独立的比赛 ID，并将第 0 帧和后续模拟帧立即写入本地 SQLite。数据库默认位于 `backend/data/matches.sqlite3`，可通过 `QUIDDITCH_DB_PATH` 指定路径。每帧包含完整的比赛状态和当步事件。服务重启会开新局，旧帧仍可通过历史接口查询。

| 方法 | 路径 | API 消息 | 用途 |
| --- | --- | --- | --- |
| GET | `/health` | — | 检查服务 |
| POST | `/auth/register` | `RegisterMessage` | 注册账号，提交 `username` 和 `password` |
| POST | `/auth/login` | `LoginMessage` | 校验密码并签发会话令牌 |
| POST | `/auth/logout` | `LogoutMessage` | 撤销请求携带的会话令牌 |
| GET | `/matches/lobby` | `GetLobbyMessage` | 获取各人数模式的球员卡、默认站位和开赛状态 |
| POST | `/matches/demo/start` | `StartMatchMessage` | 开赛，可提交 `teamSize`、`watch` 和首发球员 ID `athlete` |
| GET | `/matches/demo` | `GetMatchSnapshotMessage` | 获取最近模拟帧 |
| POST | `/matches/demo/controls` | `ApplyControlMessage` | 将操作排入下一模拟步 |
| GET | `/matches/demo/timeline/{tick}` | `GetMatchFrameMessage` | 按模拟帧编号查询当前比赛快照和事件 |
| GET | `/matches/history` | `ListMatchHistoryMessage` | 按启动时间倒序列出已记录的比赛 ID |
| GET | `/matches/history/{matchId}/timeline/{tick}` | `GetMatchFrameMessage` | 按比赛 ID 和帧号读取数据库中的快照和事件 |

比赛接口需要 `Authorization: Bearer <token>`。当前每次启动提供一场本地比赛，开赛账号拥有该场的操作权限，控制侧由后端绑定为主队。账号密码以加盐 PBKDF2 哈希存入 SQLite。会话保存在后端内存中，8 小时后或后端重启后失效。

系统通过 `InitializeUsersMessage` 和 `InitializeMatchMessage` 装配服务。HTTP 入口通过用户服务的 `ResolveSessionMessage` 解析可信身份，再把请求交给比赛路由。路由解析请求并调用消息的 `plan(connection)`。账号、球员卡和时间轴表目前只由所属服务访问。

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

七人制格兰芬多使用哈利、罗恩和 ID 5 至 9 的球员，巴塞罗那使用 ID 12 至 18 的球员。哈利和罗恩共享小场模式使用的球员卡，其余首发球员初始评分为 75。Godot 在 `client/assets/riders` 中按球员 ID 加载骑乘姿势和共用扫帚。

七人制由罗恩和若昂·加西亚分任守门员。电脑守门员在己方球环前站位，处理进入守备区的鬼飞球。格兰芬多穿深红肩背，巴塞罗那穿亮蓝肩背，守门员带白色肩块。

观战默认用倾斜的三维透视镜头显示全场。按 Tab 可切换跟球视角，滚轮缩放，按 N 显示或收起姓名牌。鬼飞球以金色标记。

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

接球先检查距离和相对速度。附近的对手正在逼近，或占住来球一侧时，可能让本来够得着的球接不稳。接球能力和稳定度越高，越能抵抗干扰。受压失手记为 `QuaffleCatchDisrupted`，该球员约 0.25 秒后可以再尝试，其余球员继续按正常规则争球。

电脑根据持球者的位置、朝向和速度决定守门、封球环路线或逼抢。只在接触方向和相对速度适合时尝试冲撞。球出手后，电脑用真实比赛的球物理预测接下来最多两秒，结合球员自身的飞行规则判断能否赶到。能接稳的球会尝试接球，较快的球则需要实际挡在球路上。算法参考了 [Google Research Football 的球路预测](https://github.com/google-research/football/blob/master/third_party/gfootball_engine/src/onthepitch/ball.cpp) 和 [RoboCup HELIOS 的拦截表](https://github.com/helios-base/librcsc/blob/develop/rcsc/player/intercept_table.cpp)。

电脑持球时按现有物理预测射门和传球路线。球环前有空间时可飞行中出手。射门路线被挡时，优先寻找能接稳球、推进或避开压迫的队友，否则带球换路线。复查时可对照传球事件、接球事件、出手速度和计分。

运行 `sbt "Test / runMain services.matches.logic.MatchChecks"` 验证接球、射门、计分和时间轴。

## 观战发现（待处理）

- **防守方有身位优势时过早回撤。** 防守球员已经占住持球者通往球门的路线，仍经常转身回去守门。后续验收时要能看到防守者保持身位，在能合法接触时主动上对抗，尝试打断进攻。

复查时使用现有电脑对战模式，通过比赛 ID 和模拟帧号对照球员位置、速度、持球状态和对抗或出手事件。

## 战术 AI 开源参考

- **Open Soccer**。先给最有威胁的接应者分配一名够近的防守人，盯防点偏向己方球门。短传接应、拉宽、前插和留位分给不同球员。球离手后保留预定接球人，可借鉴这段上下文来设计对二过一的防守。 [盯人与进攻分工](https://github.com/modelence/open-soccer/blob/main/src/client/game/engine.ts#L1787-L1935) · [传球接应](https://github.com/modelence/open-soccer/blob/main/src/client/game/engine.ts#L1215-L1234)
- **Cyrus2D**。预测持球人的推进路线，选能提前到达截断点的人。球员离开阵位的距离和冲刺消耗也参与判断，可用来改进当前按距离选逼抢者的做法。 [拦截源码](https://github.com/Cyrus2D/Cyrus2DBase/blob/cyrus2d/src/player/bhv_basic_block.cpp) · [算法说明](https://arxiv.org/html/2211.08585#S3.SS1)
- **Google Research Football**。在预测球路上比较球和球员的到达时间，并给接近上一目标的选择一定权重。可用现有飞行模拟估计拦截时机，减少防守者反复变向。 [拦截评分源码](https://github.com/google-research/football/blob/master/third_party/gfootball_engine/src/onthepitch/AIsupport/AIfunctions.cpp)
