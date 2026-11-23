# 实况魁地奇 Scala 后端

用 JDK 21 和 sbt，在本目录运行 `sbt run`。本地原型监听 `127.0.0.1:8080`，包含一场三分钟的鬼飞球比赛。Scala 以 20 Hz 推进状态，比赛重开期间记模拟帧并暂停比赛时钟。

运行 `sbt "run 8080 watch"` 可启动电脑对战。两名球员使用相同属性和 AI，使用同一个开赛接口。

每次启动分配独立的比赛 ID，并将第 0 帧和后续模拟帧立即写入本地 SQLite。数据库默认位于 `backend/data/matches.sqlite3`，可通过 `QUIDDITCH_DB_PATH` 指定路径。每帧包含完整的比赛状态和当步事件。服务重启会开新局，旧帧仍可通过历史接口查询。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/health` | 检查服务 |
| POST | `/matches/demo/start` | 开始一场比赛 |
| GET | `/matches/demo` | 获取最近模拟帧 |
| POST | `/matches/demo/controls` | 将操作排入下一模拟步 |
| GET | `/matches/demo/timeline/{tick}` | 按模拟帧编号查询历史快照和事件 |
| GET | `/matches/history` | 按启动时间倒序列出已记录的比赛 ID |
| GET | `/matches/history/{matchId}/timeline/{tick}` | 按比赛 ID 和帧号读取数据库中的快照和事件 |

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
