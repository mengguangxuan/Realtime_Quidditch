extends Node3D

const PITCH := preload("res://assets/pitch.glb")
const RiderAssets := preload("res://rider_assets.gd")
const ShotGuide := preload("res://shot_guide.gd")
const SPECTATOR_DIRECTION := Vector3(0, 0.8, 0.6)
const SPECTATOR_HOME := Color("#ff9b89")
const SPECTATOR_AWAY := Color("#9cdbff")
const SPECTATOR_BALL := Color("#ffba43")
const TAG_BACKGROUND := Color("#141b23")
const TAG_FONT_SIZE := 15
var api := MatchApi.new()
var current_state: Dictionary = {}
var read_keyboard := true
var _roster: Dictionary = {}
var _rider_visuals: Dictionary = {}
var _labels: Dictionary = {}
var _sides: Dictionary = {}
var _selected_id := ""
var _home_lineup: Array[String] = []
var _names: Dictionary = {}
var _locations: Dictionary = {}
var _headings: Dictionary = {}
var _velocities: Dictionary = {}
var _camera := Camera3D.new()
var _ball: MeshInstance3D
var shot_guide := ShotGuide.new()
var _score: Label
var _status: Label
var _controls: Label
var _spectator_overlay: Control
var _spectator_hint: Label
var _follow_ball := false
var _spectator_zoom := 1.0
var _reticle: Control
var _aiming := false
var _spectating := false
var _camera_heading := Vector3.RIGHT
var _network := false
var _brace := false
var _home_holds_ball := false
var _match_claimed := false
var _throw_power := 80.0
var _last_tick := -1
var _ruling := ""


func _ready() -> void:
	add_child(PITCH.instantiate())
	_add_lighting()
	_ball = _sphere(0.27, Color(1.0, 0.52, 0.12), 1.15)
	_ball.position = Vector3(0, 7, 0)
	add_child(_ball)
	add_child(shot_guide)
	_camera.fov = 67.0
	_camera.far = 310.0
	add_child(_camera)
	_camera.current = true
	_camera.position = Vector3(-34, 11, 0)
	_camera.look_at(Vector3(-13, 6, 0))
	_create_hud()
	api.snapshot_received.connect(_apply_frame)
	api.connection_changed.connect(func(connected: bool) -> void: _network = connected)
	api.session_expired.connect(_return_to_login)
	api.match_unavailable.connect(func() -> void:
		_match_claimed = true
		_set_aiming(false))
	add_child(api)


func _return_to_login() -> void:
	_set_aiming(false)
	get_tree().change_scene_to_file.call_deferred("res://login.tscn")


func _process(delta: float) -> void:
	if read_keyboard and not _spectating and not _match_claimed:
		if _home_holds_ball:
			shot_guide.rotate_aim(_axis(KEY_RIGHT, KEY_LEFT), _axis(KEY_UP, KEY_DOWN), delta)
		if current_state.get("phase", {}).has("InPlay"):
			_read_flight_input()
	var weight := 1.0 - exp(-20.0 * delta)
	for id: String in _locations:
		var avatar: Node3D = _roster[id]
		var target: Vector3 = _locations[id]
		avatar.position = target if avatar.position.distance_to(target) > 6.0 else avatar.position.lerp(target, weight)
		avatar.rotation.y = lerp_angle(avatar.rotation.y, _headings[id], weight)
		_update_rider_visual(_rider_visuals[id], avatar.rotation.y, _velocities[id], delta)
	_update_ball(weight)
	_update_camera(delta)
	if _spectating:
		_spectator_overlay.queue_redraw()
	_update_hud()


func _read_flight_input() -> void:
	api.flight({
		"forward": _axis(KEY_W, KEY_S),
		"strafe": _axis(KEY_D, KEY_A),
		"climb": _axis(KEY_SPACE, KEY_C),
		"turn": _axis(KEY_X, KEY_Z),
		"sprint": Input.is_physical_key_pressed(KEY_SHIFT)
	})


func _update_camera(delta: float) -> void:
	if not _roster.has(_selected_id):
		return
	if _spectating:
		_update_spectator_camera(delta)
		return
	var home: Node3D = _roster[_selected_id]
	var aim := shot_guide.direction()
	var desired_position: Vector3
	var look_target: Vector3
	if _aiming:
		var right := _horizontal(aim).cross(Vector3.UP)
		var release := home.position + aim * ShotGuide.RELEASE_OFFSET
		desired_position = home.position - aim * 4.6 + right * 1.1 + Vector3.UP * 1.35
		var depth := 22.0
		if aim.x > 0.05 and release.x < 44.0:
			depth = clampf((44.0 - release.x) / aim.x, 5.0, 45.0)
		look_target = release + aim * depth
	else:
		var target := home.transform.basis.z
		var heading := lerp_angle(atan2(_camera_heading.z, _camera_heading.x),
			atan2(target.z, target.x), 1.0 - exp(-3.5 * delta))
		_camera_heading = Vector3(cos(heading), 0, sin(heading))
		desired_position = home.position - _camera_heading * 11.0 + Vector3.UP * 5.1
		look_target = home.position + _camera_heading * 10.0 - Vector3.UP * 1.3
	desired_position.y = maxf(desired_position.y, 0.65)
	var weight := 1.0 - exp((-18.0 if _aiming else -7.0) * delta)
	_camera.position = _camera.position.lerp(desired_position, weight)
	var look_direction := (look_target - _camera.position).normalized()
	if not look_direction.is_zero_approx():
		var up := Vector3.UP if absf(look_direction.dot(Vector3.UP)) < 0.999 else Vector3.FORWARD
		_camera.look_at(look_target, up)
	_camera.fov = lerpf(_camera.fov, 54.0 if _aiming else 72.0, weight)


func _update_spectator_camera(delta: float) -> void:
	var view_up := SPECTATOR_DIRECTION.cross(Vector3.RIGHT)
	# Fit the riders on the camera plane, so width and altitude both count.
	var first: Node3D = _roster[_selected_id]
	var bounds := Rect2(first.position.x, first.position.dot(view_up), 0, 0)
	for rider: Node3D in _roster.values():
		bounds = bounds.expand(Vector2(rider.position.x, rider.position.dot(view_up)))
	if _ball.visible:
		bounds = bounds.expand(Vector2(_ball.position.x, _ball.position.dot(view_up)))
	var center := bounds.get_center()
	var target := _ball.position if _follow_ball else Vector3.RIGHT * center.x + view_up * center.y
	var viewport := get_viewport().get_visible_rect().size
	var aspect := viewport.x / maxf(viewport.y, 1)
	var overview_size := maxf(18.0, maxf(bounds.size.x / (0.78 * aspect), bounds.size.y / 0.65))
	var vertical_size := minf(22.0, overview_size * 0.6) if _follow_ball else overview_size
	var weight := 1.0 if _camera.projection != Camera3D.PROJECTION_ORTHOGONAL else 1.0 - exp(-9.0 * delta)
	_camera.projection = Camera3D.PROJECTION_ORTHOGONAL
	_camera.position = _camera.position.lerp(target + SPECTATOR_DIRECTION * 145, weight)
	_camera.look_at(_camera.position - SPECTATOR_DIRECTION)
	_camera.size = lerpf(_camera.size, vertical_size * _spectator_zoom, weight)


func _draw_spectator_overlay() -> void:
	var area := Rect2(Vector2(12, 72), _spectator_overlay.size - Vector2(24, 114))
	if area.size.x <= 0 or area.size.y <= 0:
		return
	var points: Dictionary = {}
	for id: String in _roster:
		var rider: Node3D = _roster[id]
		if not _camera.is_position_in_frustum(rider.global_position):
			continue
		points[id] = _camera.unproject_position(rider.global_position + Vector3.UP * 0.6)
	var holder := str(int(_quaffle().get("HeldBy", {}).get("athlete", -1)))
	var font := ThemeDB.fallback_font
	var plates := _spectator_nameplates(points, area, holder)
	for id: String in points:
		var point: Vector2 = points[id]
		var color := SPECTATOR_HOME if _sides[id] == "Home" else SPECTATOR_AWAY
		if plates.has(id):
			var plate: Rect2 = plates[id]
			_spectator_overlay.draw_line(point, point.clamp(plate.position, plate.end), color, 1.5, true)
		_spectator_overlay.draw_circle(point, 6, TAG_BACKGROUND, true, -1, true)
		_spectator_overlay.draw_circle(point, 4, color, true, -1, true)
	for id: String in plates:
		var plate: Rect2 = plates[id]
		var accent := SPECTATOR_HOME if _sides[id] == "Home" else SPECTATOR_AWAY
		_spectator_overlay.draw_rect(plate, TAG_BACKGROUND)
		_spectator_overlay.draw_rect(Rect2(plate.position, Vector2(3, plate.size.y)), accent)
		if id == holder:
			_spectator_overlay.draw_rect(plate, SPECTATOR_BALL, false, 2)
		_spectator_overlay.draw_string(font, plate.position + Vector2(9, 17), _names[id],
			HORIZONTAL_ALIGNMENT_LEFT, -1, TAG_FONT_SIZE, accent)
	if _ball.visible and _camera.is_position_in_frustum(_ball.global_position):
		var point := _camera.unproject_position(_ball.global_position)
		_spectator_overlay.draw_circle(point, 8, TAG_BACKGROUND, false, 2, true)
		_spectator_overlay.draw_circle(point, 7, SPECTATOR_BALL, false, 2, true)
	var home_name := "格兰芬多" if _home_lineup.size() == 7 else "主队"
	var away_name := "巴塞罗那" if _home_lineup.size() == 7 else "客队"
	var baseline := _spectator_overlay.size.y - 22
	var away_width := font.get_string_size(away_name, HORIZONTAL_ALIGNMENT_LEFT, -1, TAG_FONT_SIZE).x
	_spectator_overlay.draw_string_outline(font, Vector2(22, baseline), home_name,
		HORIZONTAL_ALIGNMENT_LEFT, -1, TAG_FONT_SIZE, 6, TAG_BACKGROUND)
	_spectator_overlay.draw_string(font, Vector2(22, baseline), home_name,
		HORIZONTAL_ALIGNMENT_LEFT, -1, TAG_FONT_SIZE, SPECTATOR_HOME)
	var away_position := Vector2(_spectator_overlay.size.x - 22 - away_width, baseline)
	_spectator_overlay.draw_string_outline(font, away_position, away_name,
		HORIZONTAL_ALIGNMENT_LEFT, -1, TAG_FONT_SIZE, 6, TAG_BACKGROUND)
	_spectator_overlay.draw_string(font, away_position, away_name,
		HORIZONTAL_ALIGNMENT_LEFT, -1, TAG_FONT_SIZE, SPECTATOR_AWAY)


func _spectator_nameplates(points: Dictionary, area: Rect2, holder: String) -> Dictionary:
	var plates: Dictionary = {}
	var font := ThemeDB.fallback_font
	var ids := points.keys()
	var markers := points.values()
	if holder in ids:
		ids.erase(holder)
		ids.push_front(holder)
	for id: String in ids:
		var caption_width := font.get_string_size(_names[id], HORIZONTAL_ALIGNMENT_LEFT, -1, TAG_FONT_SIZE).x
		var size := Vector2(ceilf(caption_width) + 18, 24)
		if size.x > area.size.x or size.y > area.size.y:
			continue
		var point: Vector2 = points[id]
		var direction := -1 if _sides[id] == "Home" else 1
		for row in range(12):
			var vertical := floorf((row + 1) * 0.5) * 28
			if row % 2 == 1:
				vertical = -vertical
			for lane: int in [0, 1, -1, 2, -2]:
				var x := point.x + direction * (11 + size.x * 0.5) + lane * 68
				var origin := (Vector2(x, point.y + vertical) - size * 0.5).clamp(area.position, area.end - size)
				var plate := Rect2(origin, size)
				if plates.values().any(func(other: Rect2) -> bool: return plate.grow(4).intersects(other)):
					continue
				if markers.any(func(marker: Vector2) -> bool: return plate.grow(6).has_point(marker)):
					continue
				plates[id] = plate
				break
			if plates.has(id):
				break
	return plates


func _horizontal(direction: Vector3) -> Vector3:
	return Vector3(direction.x, 0, direction.z).normalized()


func _update_rider_visual(visual: Node3D, heading: float, velocity: Vector3, delta: float) -> void:
	var horizontal_speed := Vector2(velocity.x, velocity.z).length()
	var offset := 0.0
	if horizontal_speed > 0.6:
		var travel_yaw := atan2(velocity.x, velocity.z)
		offset = angle_difference(heading, travel_yaw) * smoothstep(0.6, 3.0, horizontal_speed)
	visual.rotation.y = lerp_angle(visual.rotation.y, offset, 1.0 - exp(-9.0 * delta))


func _unhandled_input(event: InputEvent) -> void:
	if not read_keyboard or _match_claimed:
		return
	if _spectating:
		if event is InputEventMouseButton and event.pressed:
			if event.button_index == MOUSE_BUTTON_WHEEL_UP:
				_spectator_zoom = maxf(0.55, _spectator_zoom / 1.15)
			elif event.button_index == MOUSE_BUTTON_WHEEL_DOWN:
				_spectator_zoom = minf(1.75, _spectator_zoom * 1.15)
		return
	if event is InputEventMouseButton:
		if event.button_index == MOUSE_BUTTON_RIGHT:
			_set_aiming(event.pressed and _home_holds_ball and _network)
		elif event.button_index == MOUSE_BUTTON_LEFT and event.pressed and _aiming:
			shoot()
	elif event is InputEventMouseMotion and _aiming:
		shot_guide.aim_with_mouse(event.screen_relative)


func _set_aiming(active: bool) -> void:
	if _aiming == active:
		return
	_aiming = active
	_reticle.visible = active
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED if active else Input.MOUSE_MODE_VISIBLE


func _notification(what: int) -> void:
	if what == NOTIFICATION_APPLICATION_FOCUS_OUT and _aiming:
		_set_aiming(false)


func _unhandled_key_input(event: InputEvent) -> void:
	if not read_keyboard or _match_claimed or not event is InputEventKey or not event.pressed or event.echo:
		return
	if event.physical_keycode == KEY_ENTER and current_state.get("phase", {}).has("Ready"):
		api.start_match()
		return
	if _spectating:
		if event.physical_keycode == KEY_TAB:
			_follow_ball = not _follow_ball
			_spectator_zoom = 1.0
		return
	if current_state.get("phase", {}).has("Penalty") and event.physical_keycode not in [KEY_F, KEY_Q]:
		return
	match event.physical_keycode:
		KEY_E:
			api.control({"Catch": {"target": "Quaffle"}})
		KEY_F:
			shoot()
		KEY_R:
			pass_to_teammate()
		KEY_TAB:
			switch_teammate()
		KEY_Q:
			if _home_holds_ball:
				_reset_aim(current_state["athletes"][_selected_id]["flight"])
		KEY_B:
			_brace = not _brace
			api.control({"Brace": {"active": _brace}})
		KEY_V:
			api.control({"Charge": {}})
		KEY_G:
			api.control({"Grab": {}})


func shoot() -> void:
	if not _home_holds_ball:
		return
	var direction := shot_guide.direction()
	api.control({"Shoot": {"aim": {
		"direction": {"x": direction.x, "y": -direction.z, "z": direction.y},
		"power": ShotGuide.POWER, "spin": null
	}}})


func pass_to_teammate() -> void:
	if _home_holds_ball:
		var receiver := _pass_receiver()
		if not receiver.is_empty():
			api.control({"Pass": {"receiver": int(receiver)}})


func _pass_receiver() -> String:
	var receiver := ""
	var best_alignment := -INF
	var best_distance := INF
	var origin: Vector3 = _locations[_selected_id]
	var aim := shot_guide.direction()
	for id in _home_lineup:
		if id == _selected_id:
			continue
		var offset: Vector3 = _locations[id] - origin
		var alignment := aim.dot(offset.normalized())
		var distance := offset.length_squared()
		if alignment > best_alignment or (is_equal_approx(alignment, best_alignment) and distance < best_distance):
			receiver = id
			best_alignment = alignment
			best_distance = distance
	return receiver


func switch_teammate() -> void:
	if _home_lineup.size() > 1:
		var next := (_home_lineup.find(_selected_id) + 1) % _home_lineup.size()
		api.control({"SwitchAthlete": {"athlete": int(_home_lineup[next])}})


func _apply_frame(frame: Dictionary) -> void:
	var tick := int(frame["tick"]["value"])
	if tick == _last_tick:
		return
	_last_tick = tick
	current_state = frame["state"]
	_ruling = ""
	for event: Dictionary in frame.get("events", []):
		var result := _penalty_result(event)
		if not result.is_empty():
			_ruling = result
	var players: Array = current_state["setup"]["players"]
	_spectating = not players.is_empty() and players.all(func(player: Dictionary) -> bool:
		return player["kind"] == "Computer")
	_controls.visible = not _spectating
	_spectator_overlay.visible = _spectating
	_spectator_hint.visible = _spectating
	var previous_selection := _selected_id
	for assignment: Dictionary in current_state["controls"]:
		if assignment["player"] == "home":
			_selected_id = str(int(assignment["athlete"]))
			break
	if previous_selection != _selected_id:
		api.resend_flight()
		_set_aiming(false)
		_home_holds_ball = false
	var lineup: Array = current_state["setup"]["roster"]
	_home_lineup.clear()
	_controls.text = "WASD 移动   Z / X 转身   空格 / C 升降   E 接球   F 投球   R 传球   Tab 切换   V 冲撞" if lineup.size() > 2 else _controls_default
	for athlete: Dictionary in lineup:
		var id := str(int(athlete["id"]))
		var flight: Dictionary = current_state["athletes"][id]["flight"]
		_sides[id] = athlete["side"]
		_names[id] = athlete["name"]
		if athlete["side"] == "Home":
			_home_lineup.append(id)
		if not _roster.has(id):
			_create_rider(id, _position(flight["position"]["value"]), athlete["name"], lineup.size() > 2)
		var label: Label3D = _labels[id]
		label.visible = not _spectating and lineup.size() > 2
		label.modulate = Color.GOLD if id == _selected_id and not _spectating else Color.WHITE
		if id == _selected_id:
			_throw_power = float(athlete["ratings"]["throwing"]["power"])
	for id: String in current_state["athletes"]:
		var flight: Dictionary = current_state["athletes"][id]["flight"]
		_locations[id] = _position(flight["position"]["value"])
		_velocities[id] = _position(flight["velocity"]["value"])
		var facing := _position(flight["facing"])
		_headings[id] = atan2(facing.x, facing.z)
	_brace = current_state["athletes"][_selected_id]["movement"] == "Bracing"
	var held: Dictionary = _quaffle().get("HeldBy", {})
	var has_ball := not _spectating and str(int(held.get("athlete", -1))) == _selected_id
	if has_ball and not _home_holds_ball:
		_reset_aim(current_state["athletes"][_selected_id]["flight"])
	_home_holds_ball = has_ball
	if not _home_holds_ball:
		_set_aiming(false)


func _reset_aim(flight: Dictionary) -> void:
	shot_guide.set_direction(_position(flight["facing"]))


func _quaffle() -> Dictionary:
	var phase: Dictionary = current_state.get("phase", {})
	return phase.get("InPlay", phase.get("Penalty", {})).get("balls", {}).get("quaffle", {})


func _penalty_result(event: Dictionary) -> String:
	if event.has("PenaltyScored"):
		return "罚球命中"
	if event.has("PenaltyBlocked"):
		return "罚球被封堵"
	if event.has("PenaltyExpired"):
		return "罚球出手超时"
	if event.has("PenaltyMissed"):
		return "罚球未中"
	return ""


func _update_ball(weight: float) -> void:
	var quaffle := _quaffle()
	_ball.visible = not quaffle.is_empty()
	var held: Dictionary = quaffle.get("HeldBy", {})
	shot_guide.hide()
	if _home_holds_ball and _network:
		var rider: Node3D = _roster[_selected_id]
		var velocity: Vector3 = _velocities[_selected_id]
		shot_guide.show_tangent(rider.position, velocity, _throw_power)
	if quaffle.has("Free"):
		var goal := _position(quaffle["Free"]["motion"]["position"]["value"])
		_ball.position = goal if _ball.position.distance_to(goal) > 5.0 else _ball.position.lerp(goal, weight)
	elif not held.is_empty():
		var carrier: Node3D = _rider_visuals[str(int(held["athlete"]))]
		_ball.position = carrier.global_position + carrier.global_basis.z.normalized() * 0.75 + Vector3(0, 0.36, 0)


func _update_hud() -> void:
	if not _network:
		_set_aiming(false)
		_status.text = "连接 Scala 后端中…"
		return
	var clock: Dictionary = current_state["clock"]
	var seconds := int(ceil((clock["durationTicks"] - clock["elapsed"]["value"]) / 20.0))
	var score: Dictionary = current_state["score"]
	var named_teams := _home_lineup.size() == 7
	if _spectating:
		var view := "跟球" if _follow_ball else "全场"
		var other_view := "全场" if _follow_ball else "跟球"
		_spectator_hint.text = "%s视角   ·   Tab 切换%s   ·   滚轮缩放" % [view, other_view]
	var home := "格兰芬多" if named_teams else "绿队"
	var away := "巴塞罗那" if named_teams else "蓝队"
	var home_caption := home + "电脑" if _spectating else (home if named_teams else "你")
	var away_caption := away + "电脑" if _spectating else (away if named_teams else "电脑")
	_score.text = "%s  %d   /   %d  %s      %02d:%02d" % [
		home_caption, score["home"], score["away"], away_caption, floori(seconds / 60.0), seconds % 60
	]
	var attack = current_state.get("attackClock")
	if attack != null:
		var team := home if attack["side"] == "Home" else away
		_score.text += "  ·  %s进攻 %d 秒" % [team, ceili(attack["remainingTicks"] / 20.0)]
	if _match_claimed:
		_status.text = "此场比赛由另一个账号开启"
		return
	var phase: Dictionary = current_state["phase"]
	if phase.has("Ready"):
		_status.text = "按 Enter 开赛"
	elif phase.has("RestartPending"):
		var restart: Dictionary = phase["RestartPending"]["restart"]
		if restart.has("PenaltyThrow"):
			_status.text = "犯规，准备定点罚球"
		elif restart.has("FreePossession"):
			_status.text = "犯规，受犯规方持球恢复"
		elif restart.has("TeamPossession"):
			var after: Dictionary = restart["TeamPossession"]["after"]
			if after.has("AttackExpired"):
				_status.text = "进攻超时，球权交给对方"
			elif after.has("QuaffleGoal"):
				_status.text = "进球，失分方持球恢复"
			else:
				_status.text = _penalty_result(after)
		else:
			_status.text = "比赛暂停，即将恢复球权"
	elif phase.has("Finished"):
		_status.text = "比赛结束" if _ruling.is_empty() else "比赛结束  ·  " + _ruling
	elif phase.has("Penalty"):
		var penalty: Dictionary = phase["Penalty"]
		if _quaffle().has("Free"):
			_status.text = "罚球飞行中"
		else:
			var remaining := ceili(penalty["remainingTicks"] / 20.0)
			var team := home if penalty["side"] == "Home" else away
			_status.text = "你来罚球  ·  右键瞄准，F 或左键出手  ·  %d 秒" % remaining if _home_holds_ball else "%s罚球  ·  %d 秒" % [team, remaining]
	elif _spectating:
		var holder := int(_quaffle().get("HeldBy", {}).get("athlete", -1))
		if holder < 0:
			_status.text = "观战  ·  自由球"
		else:
			_status.text = "观战  ·  %s  ·  %s 持球" % [
				home if _sides[str(holder)] == "Home" else away, _names[str(holder)]
			]
	else:
		var quaffle := _quaffle()
		if quaffle.has("HeldBy"):
			var holder_id := str(int(quaffle["HeldBy"]["athlete"]))
			if holder_id == _selected_id:
				_status.text = "你持球  ·  按住右键瞄准  ·  F 或左键投球"
				if named_teams:
					_status.text += "  ·  R 传给 " + str(_names.get(_pass_receiver(), ""))
			elif _home_lineup.has(holder_id):
				_status.text = "队友持球  ·  Tab 切换"
			else:
				_status.text = "对手持球  ·  V 冲撞"
		else:
			_status.text = "追上橙色鬼飞球后按 E 接球"


func _create_rider(id: String, initial: Vector3, rider_name: String, show_name: bool) -> void:
	var avatar := Node3D.new()
	add_child(avatar)
	var visual: Node3D = RiderAssets.create(int(id))
	avatar.add_child(visual)
	_rider_visuals[id] = visual
	avatar.position = initial
	var home: bool = _sides[id] == "Home"
	avatar.rotation.y = PI / 2.0 if home else -PI / 2.0
	var badge := _sphere(0.20, Color(0.36, 1.0, 0.57) if home else Color(0.30, 0.66, 1.0), 1.0)
	badge.visible = not _spectating
	avatar.add_child(badge)
	badge.position = Vector3(0, 1.4, 0)
	var label := Label3D.new()
	label.text = rider_name
	label.position = Vector3(0, 1.95, 0)
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	label.pixel_size = 0.005
	label.visible = show_name
	avatar.add_child(label)
	_labels[id] = label
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


const _controls_default := "WASD 移动   Z / X 转身   空格 / C 升降   Shift 冲刺   E 接球   右键瞄准   F 投球   V 冲撞   G 拉拽   B 护球"


func _create_hud() -> void:
	var ui := CanvasLayer.new()
	add_child(ui)
	_spectator_overlay = Control.new()
	_spectator_overlay.mouse_filter = Control.MOUSE_FILTER_IGNORE
	ui.add_child(_spectator_overlay)
	_spectator_overlay.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	_spectator_overlay.draw.connect(_draw_spectator_overlay)
	_spectator_overlay.hide()
	var hud := VBoxContainer.new()
	ui.add_child(hud)
	hud.set_anchors_and_offsets_preset(Control.PRESET_TOP_WIDE)
	hud.add_theme_constant_override("separation", 5)
	_score = _line(hud, "", 25)
	_status = _line(hud, "连接 Scala 后端中…", 18)
	_reticle = Control.new()
	_reticle.mouse_filter = Control.MOUSE_FILTER_IGNORE
	ui.add_child(_reticle)
	_reticle.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	for rect: Rect2 in [Rect2(-10, -1, 6, 2), Rect2(4, -1, 6, 2), Rect2(-1, -10, 2, 6), Rect2(-1, 4, 2, 6)]:
		var line := ColorRect.new()
		line.mouse_filter = Control.MOUSE_FILTER_IGNORE
		line.color = Color(0.96, 1.0, 0.96)
		line.position = rect.position
		line.size = rect.size
		_reticle.add_child(line)
	_reticle.hide()
	_controls = _line(ui, _controls_default, 16)
	_controls.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	_controls.offset_bottom = -16
	_spectator_hint = _line(ui, "", 16)
	_spectator_hint.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_WIDE)
	_spectator_hint.offset_bottom = -16
	_spectator_hint.hide()


func _line(parent: Node, text_value: String, size: int) -> Label:
	var label := Label.new()
	label.mouse_filter = Control.MOUSE_FILTER_IGNORE
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
