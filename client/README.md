# 实况魁地奇 Godot 本地原型

在 `Quidditch` 目录用 PowerShell 运行 `.\play-local.ps1`。它会编译 Scala 后端，启动一场人机比赛，并打开 Godot 游戏窗口。首次运行需要 JDK 和 Maven Central 依赖，默认使用本机 `E:\Godot` 中的 Godot。如路径不同，传入 `-GodotExe`。关闭游戏窗口会停止该次启动的后端。

运行 `.\play-local.ps1 -Watch` 可观战。按 Enter 开赛，绿队和蓝队由同一套 AI 操控，球员属性相同。侧上方镜头会跟随双方和鬼飞球。用 `.\play-local.ps1 -Watch -Smoke` 验证观战模式。

| 操作 | 键位 |
| --- | --- |
| 开赛 | Enter |
| 相对球员朝向前进、后退和横移 | WASD |
| 球员向左、向右转身 | Z、X |
| 上升和下降 | 空格、C |
| 冲刺 | Shift |
| 接鬼飞球 | E |
| 肩后瞄准 | 持球时按住右键，移动鼠标转向和调整仰角 |
| 键盘微调 | ← → 左右转，↑ ↓ 抬高或压低，Q 回到球员朝向 |
| 投球 | F，或瞄准时按左键 |
| 冲撞、拉拽、护球 | V、G、B |

橙色球是鬼飞球。宽视角从球员身后跟随，瞄准时切到带准星的肩后镜头。两种视角下，WASD 始终沿球员自身的前后左右移动。绿色箭头表示预计出手瞬间的轨迹切线，会随手动瞄准和球员当前速度偏转。投远球时需要抬高方向，为下坠留余量。后端仍会结算出手偏差、碰撞、得分、犯规和计时，Godot 插值显示位置。需要再打一场时，关闭游戏后重运行脚本。

引擎项目入口为 `project.godot`。手动启动后端时可运行 `sbt "run 8080 cpu"`，再运行 Godot 项目。客户端默认连接 `127.0.0.1:8080`，也可通过 `QUIDDITCH_BACKEND_URL` 覆盖。使用 `.\play-local.ps1 -Smoke` 可以在后台验证实际 Godot 客户端与 Scala 后端的开赛、飞行、接球和投球得分链路。

球场来自本地 `Quidditch_Pitch.blend`，骑手客户端资产取自 `Quidditch_Harry_Takeoff.blend` 的飞行关键帧。角色模型由 RRB STUDIOS 上传，模型页标注 CC BY 4.0。本地原型复用了飞行姿态，完整拿帚和起飞动画在 Blender 源文件内。 [角色模型来源](https://sketchfab.com/3d-models/harry-potter-rigged-character-250a590d181e4f6cb0aec4a0c55e293c)
