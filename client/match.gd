extends Node3D

const PITCH := preload("res://assets/pitch.glb")
const RIDER := preload("res://assets/rider.glb")
const RINGS := [Vector3(44, 8.4, 7.1), Vector3(44, 12, 0), Vector3(44, 10, -7.1)]
const HOME_ID := "1"

var api := MatchApi.new()
var current_state: Dictionary = {}
var read_keyboard := true
var _roster: Dictionary = {}
var _locations: Dictionary = {}
var _headings: Dictionary = {}
var _camera := Camera3D.new()
var _ball: MeshInstance3D
var _aim_marker: MeshInstance3D
var _score: Label
var _status: Label
var _network := false
var _brace := false
var _target_ring := 1
var _last_tick := -1


func _ready() -> void:
	add_child(PITCH.instantiate())
	_add_lighting()
	_create_rider("1", Vector3(-23, 7, 0))
	_create_rider("2", Vector3(23, 7, 0))
	_ball = _sphere(0.27, Color(1.0, 0.52, 0.12), 1.15)
	_ball.position = Vector3(0, 7, 0)
	add_child(_ball)
	_aim_marker = _sphere(0.21, Color(0.34, 1.0, 0.66), 2.0)
	add_child(_aim_marker)
	_camera.fov = 67.0
	_camera.far = 310.0
	add_child(_camera)
	_camera.current = true
	_camera.position = Vector3(-34, 11, 0)
	_camera.look_at(Vector3(-13, 6, 0))
	_create_hud()
	api.snapshot_received.connect(_apply_frame)
	api.connection_changed.connect(func(connected: bool) -> void: _network = connected)
	add_child(api)


func _process(delta: float) -> void:
	if read_keyboard:
		api.flight({
			"forward": _axis(KEY_W, KEY_S),
			"strafe": _axis(KEY_A, KEY_D),
			"climb": _axis(KEY_SPACE, KEY_C),
			"sprint": Input.is_physical_key_pressed(KEY_SHIFT)
		})
	var weight := 1.0 - exp(-20.0 * delta)
	for id: String in _locations:
		var avatar: Node3D = _roster[id]
		var target: Vector3 = _locations[id]
		avatar.position = target if avatar.position.distance_to(target) > 6.0 else avatar.position.lerp(target, weight)
		avatar.rotation.y = lerp_angle(avatar.rotation.y, _headings[id], weight)
	var home: Node3D = _roster[HOME_ID]
	var camera_weight := 1.0 - exp(-8.0 * delta)
	_camera.position = _camera.position.lerp(home.position + Vector3(-9.4, 3.3, 0), camera_weight)
	_camera.look_at(home.position + Vector3(12, -2.1, 0))
	_update_ball(weight)
	_update_hud()


func _unhandled_key_input(event: InputEvent) -> void:
	if not read_keyboard or not event is InputEventKey or not event.pressed or event.echo:
		return
	match event.physical_keycode:
		KEY_ENTER:
			if current_state.get("phase", {}).has("Ready"):
				api.start_match()
		KEY_E:
			api.control({"Catch": {"target": "Quaffle"}})
		KEY_F:
			shoot()
		KEY_Q:
			_target_ring = (_target_ring + 1) % RINGS.size()
		KEY_B:
			_brace = not _brace
			api.control({"Brace": {"active": _brace}})
		KEY_V:
			api.control({"Charge": {}})
		KEY_G:
			api.control({"Grab": {}})


func shoot() -> void:
	var athlete: Dictionary = current_state.get("athletes", {}).get(HOME_ID, {})
	if athlete.is_empty():
		return
	var flight: Dictionary = athlete["flight"]
	var point := _position(flight["position"]["value"])
	var velocity := _position(flight["velocity"]["value"])
	var ring: Vector3 = RINGS[_target_ring]
	var travel_time := clampf(point.distance_to(ring) / 32.0, 0.12, 1.25)
	var compensated := ring - point - velocity * travel_time + Vector3.UP * (4.905 * travel_time * travel_time)
	var direction := compensated.normalized()
	api.control({"Shoot": {"aim": {
		"direction": {"x": direction.x, "y": -direction.z, "z": direction.y},
		"power": 1.0, "spin": null
	}}})


func _apply_frame(frame: Dictionary) -> void:
	var tick := int(frame["tick"]["value"])
	if tick == _last_tick:
		return
	_last_tick = tick
	current_state = frame["state"]
	for id: String in current_state["athletes"]:
		var flight: Dictionary = current_state["athletes"][id]["flight"]
		_locations[id] = _position(flight["position"]["value"])
		var facing := _position(flight["facing"])
		_headings[id] = atan2(facing.x, facing.z)
	_brace = current_state["athletes"][HOME_ID]["movement"] == "Bracing"


func _quaffle() -> Dictionary:
	return current_state.get("phase", {}).get("InPlay", {}).get("balls", {}).get("quaffle", {})


func _update_ball(weight: float) -> void:
	var quaffle := _quaffle()
	_ball.visible = not quaffle.is_empty()
	var held: Dictionary = quaffle.get("HeldBy", {})
	_aim_marker.visible = not held.is_empty() and int(held["athlete"]) == int(HOME_ID)
	_aim_marker.position = RINGS[_target_ring]
	if quaffle.has("Free"):
		var goal := _position(quaffle["Free"]["motion"]["position"]["value"])
		_ball.position = goal if _ball.position.distance_to(goal) > 5.0 else _ball.position.lerp(goal, weight)
	elif not held.is_empty():
		var carrier: Node3D = _roster[str(int(held["athlete"]))]
		_ball.position = carrier.position + carrier.transform.basis.z * 0.75 + Vector3(0, 0.36, 0)


func _update_hud() -> void:
	if not _network:
		_status.text = "连接 Scala 后端中…"
		return
	var clock: Dictionary = current_state["clock"]
	var seconds := int(ceil((clock["durationTicks"] - clock["elapsed"]["value"]) / 20.0))
	var score: Dictionary = current_state["score"]
	_score.text = "你  %d   /   %d  电脑      %02d:%02d" % [
		score["home"], score["away"], floori(seconds / 60.0), seconds % 60
	]
	var phase: Dictionary = current_state["phase"]
	if phase.has("Ready"):
		_status.text = "按 Enter 开赛"
	elif phase.has("RestartPending"):
		_status.text = "比赛暂停，即将重新开球"
	elif phase.has("Finished"):
		_status.text = "比赛结束"
	else:
		var quaffle := _quaffle()
		if quaffle.has("HeldBy"):
			var home_has_ball := int(quaffle["HeldBy"]["athlete"]) == int(HOME_ID)
			_status.text = "你持球  ·  F 投向选中的球门  ·  Q 切换" if home_has_ball else "电脑持球  ·  V 冲撞"
		else:
			_status.text = "追上橙色鬼飞球后按 E 接球"


func _create_rider(id: String, initial: Vector3) -> void:
	var avatar: Node3D = RIDER.instantiate()
	add_child(avatar)
	avatar.position = initial
	avatar.rotation.y = PI / 2.0 if id == HOME_ID else -PI / 2.0
	var badge := _sphere(0.20, Color(0.36, 1.0, 0.57) if id == HOME_ID else Color(0.30, 0.66, 1.0), 1.0)
	avatar.add_child(badge)
	badge.position = Vector3(0, 1.4, 0)
	_roster[id] = avatar


func _add_lighting() -> void:
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, -34, 0)
	sun.light_energy = 1.28
	sun.shadow_enabled = true
	add_child(sun)
	var environment := WorldEnvironment.new()
	environment.environment = Environment.new()
	environment.environment.background_mode = Environment.BG_COLOR
	environment.environment.background_color = Color(0.57, 0.70, 0.82)
	environment.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.environment.ambient_light_color = Color(0.83, 0.89, 1)
	environment.environment.ambient_light_energy = 0.72
	add_child(environment)


func _create_hud() -> void:
	var ui := CanvasLayer.new()
	add_child(ui)
	var hud := VBoxContainer.new()
	ui.add_child(hud)
	hud.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	hud.add_theme_constant_override("separation", 5)
	_score = _line(hud, "", 25)
	_status = _line(hud, "连接 Scala 后端中…", 18)
	var controls := _line(ui, "WASD 移动   空格 / C 升降   Shift 冲刺   E 接球   F 投球   Q 切环   V 冲撞   G 拉拽   B 护球", 16)
	controls.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	controls.offset_bottom = -16


func _line(parent: Node, text_value: String, size: int) -> Label:
	var label := Label.new()
	label.text = text_value
	label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	label.add_theme_font_size_override("font_size", size)
	label.add_theme_color_override("font_outline_color", Color(0.08, 0.13, 0.17))
	label.add_theme_constant_override("outline_size", 7)
	parent.add_child(label)
	return label


func _sphere(radius: float, color: Color, emission: float) -> MeshInstance3D:
	var instance := MeshInstance3D.new()
	var mesh := SphereMesh.new()
	mesh.radius = radius
	mesh.height = radius * 2.0
	instance.mesh = mesh
	var material := StandardMaterial3D.new()
	material.albedo_color = color
	material.emission_enabled = true
	material.emission = color
	material.emission_energy_multiplier = emission
	instance.material_override = material
	return instance


func _axis(positive: int, negative: int) -> float:
	return float(Input.is_physical_key_pressed(positive)) - float(Input.is_physical_key_pressed(negative))


func _position(value: Dictionary) -> Vector3:
	return Vector3(value["x"], value["z"], -value["y"])
