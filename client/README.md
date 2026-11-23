# 实况魁地奇 Godot 本地原型

在 `Quidditch` 目录用 PowerShell 运行 `.\play-local.ps1`。它会编译 Scala 后端，启动一场人机比赛，并打开 Godot 游戏窗口。首次运行需要 JDK 和 Maven Central 依赖，默认使用本机 `E:\Godot` 中的 Godot。如路径不同，传入 `-GodotExe`。关闭游戏窗口会停止该次启动的后端。

| 操作 | 键位 |
| --- | --- |
| 开赛 | Enter |
| 前后和横移 | WASD |
| 上升和下降 | 空格、C |
| 冲刺 | Shift |
| 接鬼飞球 | E |
| 投球、切换目标环 | F、Q |
| 冲撞、拉拽、护球 | V、G、B |

橙色球是鬼飞球。持球后绿点会显示当前瞄准的球环，飞进对方半场后投球更容易得分。后端决定速度、碰撞、得分、犯规和计时，Godot 插值显示位置。需要再打一场时，关闭游戏后重运行脚本。

引擎项目入口为 `project.godot`。手动启动后端时可运行 `sbt "run 8080 cpu"`，再运行 Godot 项目。客户端默认连接 `127.0.0.1:8080`，也可通过 `QUIDDITCH_BACKEND_URL` 覆盖。使用 `.\play-local.ps1 -Smoke` 可以在后台验证实际 Godot 客户端与 Scala 后端的开赛、飞行、接球和投球得分链路。

球场来自本地 `Quidditch_Pitch.blend`，骑手客户端资产取自 `Quidditch_Harry_Takeoff.blend` 的飞行关键帧。角色模型由 RRB STUDIOS 上传，模型页标注 CC BY 4.0。本地原型复用了飞行姿态，完整拿帚和起飞动画在 Blender 源文件内。 [角色模型来源](https://sketchfab.com/3d-models/harry-potter-rigged-character-250a590d181e4f6cb0aec4a0c55e293c)
