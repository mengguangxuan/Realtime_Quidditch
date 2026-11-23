# 实况魁地奇 Scala 后端

用 JDK 21 和 sbt，在本目录运行 `sbt run`。本地原型监听 `127.0.0.1:8080`，包含一场三分钟的鬼飞球比赛。Scala 以 20 Hz 推进状态，比赛重开期间记模拟帧并暂停比赛时钟。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/health` | 检查服务 |
| POST | `/matches/demo/start` | 开始一场比赛 |
| GET | `/matches/demo` | 获取最近模拟帧 |
| POST | `/matches/demo/controls` | 将操作排入下一模拟步 |
| GET | `/matches/demo/timeline/{tick}` | 按模拟帧编号查询历史快照和事件 |

本地调试使用 `home` 和 `away` 两个控制者。开赛后可向 `/matches/demo/controls` 发送下面的 JSON，设置主队球员的移动输入。

```json
{"player":"home","control":{"Fly":{"input":{"forward":1,"strafe":0,"climb":0,"sprint":false}}}}
```

首版场地方向为 X 轴攻防、Y 轴横移、Z 轴升降。移动输入一直生效，直到收到下一次 `Fly` 或比赛重开。停止时将三根轴设为零。发送 `{"player":"home","control":{"Catch":{"target":"Quaffle"}}}` 尝试接球。操作返回排入的模拟帧编号。等最新帧达到该编号后，可以查询对应历史帧的 `events`。

持球后可提交投球意图。以下示例朝 X 轴正方向全力出手。服务会归一化方向向量，`spin` 可为 `null`，或介于 −1 与 1 之间的数值。

```json
{"player":"home","control":{"Shoot":{"aim":{"direction":{"x":1,"y":0,"z":0},"power":1,"spin":null}}}}
```

距离、出手方向与身体朝向的偏差、速度、终结、平衡和临场稳定度共同影响投球准度。球员命中来球时，鬼飞球会反弹并保持自由球状态。一次模拟步里，先撞到球员会触发 `QuaffleBlocked`，先穿过球环则记 10 分。进球或吹停后等待一秒继续比赛。当前时间轴保存在进程内，游走球和飞贼已建模，首版比赛只启用鬼飞球。

运行 `sbt "Test / runMain services.matches.logic.MatchChecks"` 验证接球、射门、计分和时间轴。
