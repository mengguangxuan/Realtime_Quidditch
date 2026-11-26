extends Control

const MATCH_SCENE := preload("res://match.tscn")
const RiderAssets := preload("res://rider_assets.gd")
const GOLD := Color("#e5c77a")
const INK := Color("#f1eee0")

var token := ""
var _request := HTTPRequest.new()
var _modes := OptionButton.new()
var _tactics_panel := HBoxContainer.new()
var _attack_support := OptionButton.new()
var _defense := OptionButton.new()
var _cards_grid := HBoxContainer.new()
var _opponents_grid := HBoxContainer.new()
var _detail := Label.new()
var _status := Label.new()
var _play := Button.new()
var _preview := Node3D.new()
var _formation_panel := VBoxContainer.new()
var _formation_player := OptionButton.new()
var _formation_pitch := Control.new()
var _formation_status := Label.new()
var _positioning: Dictionary = {}
var _formation_edits: Dictionary = {}
var _opening_players: Array[Dictionary] = []
var _cards: Array = []
var _chosen: Dictionary = {}
var _starting := false
var _active_match := false


func _ready() -> void:
	var backdrop := ColorRect.new()
	backdrop.color = Color("#14241f")
	add_child(backdrop)
	backdrop.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var layout := HBoxContainer.new()
	layout.add_theme_constant_override("separation", 36)
	add_child(layout)
	layout.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	layout.offset_left = 46
	layout.offset_right = -46
	layout.offset_top = 46
	layout.offset_bottom = -46
	var form := VBoxContainer.new()
	form.custom_minimum_size.x = 510
	form.add_theme_constant_override("separation", 12)
	layout.add_child(form)
	_label(form, "比赛准备", 35, GOLD)
	_label(form, "对战模式", 18, INK)
	for option: String in ["1 对 1  ·  人机对战", "2 对 2  ·  人机对战", "1 对 1  ·  电脑观战", "2 对 2  ·  电脑观战"]:
		_modes.add_item(option)
	_modes.custom_minimum_size.y = 46
	form.add_child(_modes)
	_tactics_panel.add_theme_constant_override("separation", 12)
	form.add_child(_tactics_panel)
	_add_tactics_option(_attack_support, "格兰芬多进攻接应", ["拉开接应", "近距离接应"], ["Wide", "Nearby"])
	_add_tactics_option(_defense, "格兰芬多防守", ["上前逼抢", "回撤保护"], ["Pressure", "Cover"])
	_modes.item_selected.connect(_mode_changed)
	_update_tactics_visibility(_modes.selected)
	_label(form, "选择格兰芬多首发", 18, INK)
	form.add_child(_cards_grid)
	_label(form, "查看斯莱特林球员", 16, INK)
	form.add_child(_opponents_grid)
	_detail.custom_minimum_size.y = 112
	_detail.add_theme_constant_override("line_spacing", 6)
	form.add_child(_detail)
	_play.text = "开始比赛"
	_play.custom_minimum_size.y = 46
	_play.disabled = true
	_play.pressed.connect(_start)
	form.add_child(_play)
	_status.text = "正在读取球员卡…"
	form.add_child(_status)
	var right := VBoxContainer.new()
	right.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	right.add_theme_constant_override("separation", 12)
	layout.add_child(right)
	_create_formation_editor(right)
	_create_preview(right)
	add_child(_request)
	_request.timeout = 8
	_request.request_completed.connect(_completed)
	_request.request(MatchApi.backend_origin() + "/matches/lobby", _headers())


func _add_tactics_option(option: OptionButton, caption: String, labels: Array[String], values: Array[String]) -> void:
	var column := VBoxContainer.new()
	column.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_tactics_panel.add_child(column)
	_label(column, caption, 16, INK)
	option.custom_minimum_size.y = 36
	for index in labels.size():
		option.add_item(labels[index])
		option.set_item_metadata(index, values[index])
	column.add_child(option)


func _update_tactics_visibility(mode: int) -> void:
	_tactics_panel.visible = mode % 2 == 1


func _mode_changed(mode: int) -> void:
	_update_tactics_visibility(mode)
	_refresh_opening_players()


func _create_formation_editor(parent: Control) -> void:
	parent.add_child(_formation_panel)
	_label(_formation_panel, "格兰芬多开场站位", 18, GOLD)
	var actions := HBoxContainer.new()
	_formation_panel.add_child(actions)
	_formation_player.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	_formation_player.item_selected.connect(func(_index: int) -> void: _formation_pitch.queue_redraw())
	actions.add_child(_formation_player)
	var reset := Button.new()
	reset.text = "恢复默认"
	reset.pressed.connect(func() -> void:
		_formation_edits.clear()
		_refresh_opening_players())
	actions.add_child(reset)
	_formation_pitch.custom_minimum_size.y = 156
	_formation_panel.add_child(_formation_pitch)
	_formation_pitch.draw.connect(_draw_formation)
	_formation_pitch.gui_input.connect(_place_player)
	_formation_pitch.resized.connect(_formation_pitch.queue_redraw)
	_formation_panel.add_child(_formation_status)


func _refresh_opening_players() -> void:
	_opening_players.clear()
	_formation_player.clear()
	if _chosen.is_empty() or _positioning.is_empty():
		_play.disabled = _starting or _chosen.is_empty()
		return
	_opening_players.append(_chosen)
	var team_size := _modes.selected % 2 + 1
	for card: Dictionary in _cards:
		if _opening_players.size() >= team_size:
			break
		if card["side"] == "Home" and card["id"] != _chosen["id"]:
			_opening_players.append(card)
	for card: Dictionary in _opening_players:
		_formation_player.add_item(card["name"])
	_formation_player.select(0)
	var valid := _valid_formation()
	_formation_status.text = "选球员后，点击左侧可用区域摆位。" if valid else "球员站位太近，请调整或恢复默认。"
	_play.disabled = _starting or not valid
	_formation_pitch.queue_redraw()


func _starting_point(index: int) -> Vector2:
	var slot: Dictionary = _positioning["slots"][index]["value"]
	return _formation_edits.get(int(_opening_players[index]["id"]), Vector2(slot["x"], slot["y"]))


func _valid_formation() -> bool:
	var spacing := float(_positioning["spacing"])
	for index in _opening_players.size():
		for other in range(index + 1, _opening_players.size()):
			if _starting_point(index).distance_to(_starting_point(other)) < spacing:
				return false
	return true


func _field_extents() -> Vector2:
	return Vector2(_positioning["halfLength"], _positioning["halfWidth"])


func _pitch_rect() -> Rect2:
	return Rect2(Vector2(14, 8), _formation_pitch.size - Vector2(28, 16))


func _map_position(point: Vector2) -> Vector2:
	var rect := _pitch_rect()
	return rect.get_center() + point / _field_extents() * rect.size * 0.5


func _draw_formation() -> void:
	if _positioning.is_empty() or _formation_pitch.size.x <= 28:
		return
	var extents := _field_extents()
	var outline := PackedVector2Array()
	for index in 48:
		outline.append(_map_position(Vector2.from_angle(TAU * index / 48.0) * extents))
	_formation_pitch.draw_colored_polygon(outline, Color("#28463a"))
	outline.append(outline[0])
	_formation_pitch.draw_polyline(outline, Color("#a6b7a5"), 1.5, true)
	var available := PackedVector2Array()
	for direction in [1, -1]:
		for index in 25:
			var fraction := index / 24.0 if direction == 1 else 1.0 - index / 24.0
			var x := lerpf(-float(_positioning["goalPlaneX"]), -float(_positioning["clearance"]), fraction)
			var y := extents.y * sqrt(maxf(0, 1 - pow(x / extents.x, 2)))
			available.append(_map_position(Vector2(x, y * direction)))
	_formation_pitch.draw_colored_polygon(available, Color("#37634f"))
	_formation_pitch.draw_line(_map_position(Vector2(0, -extents.y)), _map_position(Vector2(0, extents.y)),
		Color("#a6b7a5"), 1)
	for index in _opening_players.size():
		var point := _map_position(_starting_point(index))
		var selected := index == _formation_player.selected
		_formation_pitch.draw_circle(point, 7 if selected else 5, GOLD if selected else INK)
		var caption := str(_opening_players[index]["name"]).get_slice(" ", 0)
		_formation_pitch.draw_string(ThemeDB.fallback_font, point + Vector2(9, 4), caption,
			HORIZONTAL_ALIGNMENT_LEFT, -1, 13, INK)


func _place_player(event: InputEvent) -> void:
	if _starting or _active_match or _formation_player.selected < 0 or _positioning.is_empty():
		return
	if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT and event.pressed:
		var rect := _pitch_rect()
		var point: Vector2 = (event.position - rect.get_center()) * 2 / rect.size * _field_extents()
		if (point.x < -float(_positioning["goalPlaneX"]) or point.x > -float(_positioning["clearance"])
				or (point / _field_extents()).length() > 1):
			_formation_status.text = "请选择左侧标出的站位区域。"
			return
		var selected := _formation_player.selected
		for index in _opening_players.size():
			if index != selected and point.distance_to(_starting_point(index)) < float(_positioning["spacing"]):
				_formation_status.text = "与队友至少保持 %d 米。" % int(_positioning["spacing"])
				return
		_formation_edits[int(_opening_players[selected]["id"])] = point
		_formation_status.text = "已更新 %s 的开场站位。" % _opening_players[selected]["name"]
		_play.disabled = not _valid_formation()
		_formation_pitch.queue_redraw()
		_formation_pitch.accept_event()


func _select_tactic(option: OptionButton, value: String) -> void:
	for index in option.item_count:
		if option.get_item_metadata(index) == value:
			option.select(index)
			return


func _label(parent: Control, caption: String, size: int, color: Color) -> void:
	var label := Label.new()
	label.text = caption
	label.add_theme_font_size_override("font_size", size)
	label.add_theme_color_override("font_color", color)
	parent.add_child(label)


func _create_preview(parent: Control) -> void:
	var container := SubViewportContainer.new()
	container.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	container.size_flags_vertical = Control.SIZE_EXPAND_FILL
	container.stretch = true
	container.mouse_filter = Control.MOUSE_FILTER_IGNORE
	parent.add_child(container)
	var viewport := SubViewport.new()
	viewport.own_world_3d = true
	viewport.transparent_bg = true
	container.add_child(viewport)
	viewport.add_child(_preview)
	var light := DirectionalLight3D.new()
	light.rotation_degrees = Vector3(-30, 125, 0)
	light.light_energy = 2.0
	viewport.add_child(light)
	var fill := OmniLight3D.new()
	fill.position = Vector3(-1.7, 1.8, -0.3)
	fill.omni_range = 8
	fill.light_energy = 0.8
	viewport.add_child(fill)
	var camera := Camera3D.new()
	viewport.add_child(camera)
	camera.position = Vector3(2.1, 0.85, 2.6)
	camera.look_at(Vector3(0, 0.18, 0))
	camera.fov = 34
	camera.current = true


func _headers() -> PackedStringArray:
	return ["Authorization: Bearer " + token, "Content-Type: application/json"]


func _show_cards() -> void:
	var group := ButtonGroup.new()
	for card: Dictionary in _cards:
		var home: bool = card["side"] == "Home"
		var button := Button.new()
		button.text = card["name"]
		button.custom_minimum_size.y = 46
		button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		if home:
			button.toggle_mode = true
			button.button_group = group
			button.pressed.connect(_choose.bind(card))
			_cards_grid.add_child(button)
			if _chosen.is_empty():
				button.set_pressed_no_signal(true)
				_choose(card)
		else:
			button.pressed.connect(_show_card.bind(card))
			_opponents_grid.add_child(button)


func _choose(card: Dictionary) -> void:
	if _starting or _active_match:
		return
	_chosen = card
	_show_card(card)
	_refresh_opening_players()


func _show_card(card: Dictionary) -> void:
	var ratings: Dictionary = card["ratings"]
	_detail.text = "%s\n飞行速度  %d     转向  %d\n身体对抗  %d     接球  %d\n终结  %d     创造力  %d" % [
		card["name"], ratings["flight"]["speed"], ratings["flight"]["turning"],
		ratings["physical"]["strength"], ratings["ballControl"]["catching"],
		ratings["throwing"]["finishing"], ratings["awareness"]["creativity"]
	]
	for child in _preview.get_children():
		child.queue_free()
	_preview.add_child(RiderAssets.create(int(card["id"])))


func _start() -> void:
	if _active_match:
		_enter_match()
		return
	if _starting or _chosen.is_empty() or _play.disabled:
		return
	_starting = true
	_play.disabled = true
	_status.text = "正在开赛…"
	var payload := {"athlete": int(_chosen["id"]), "teamSize": _modes.selected % 2 + 1,
		"watch": _modes.selected >= 2}
	if _tactics_panel.visible:
		payload["tactics"] = {
			"attackSupport": _attack_support.get_item_metadata(_attack_support.selected),
			"defense": _defense.get_item_metadata(_defense.selected)
		}
	var positions: Dictionary = {}
	for index in _opening_players.size():
		var id := int(_opening_players[index]["id"])
		if _formation_edits.has(id):
			var point: Vector2 = _formation_edits[id]
			var slot: Dictionary = _positioning["slots"][index]["value"]
			positions[str(id)] = {"value": {"x": point.x, "y": point.y, "z": slot["z"]}}
	if not positions.is_empty():
		payload["startingPositions"] = positions
	if _request.request(MatchApi.backend_origin() + "/matches/demo/start", _headers(),
			HTTPClient.METHOD_POST, JSON.stringify(payload)) != OK:
		_starting = false
		_play.disabled = false
		_status.text = "无法连接比赛服务器。"


func _completed(result: int, code: int, _response_headers: PackedStringArray, body: PackedByteArray) -> void:
	if code == 401:
		get_tree().change_scene_to_file.call_deferred("res://login.tscn")
		return
	if _starting:
		if result == HTTPRequest.RESULT_SUCCESS and code == 200:
			_enter_match()
			return
		_starting = false
		_play.disabled = false
		_status.text = "比赛已开启，请重启一场。" if code == 409 else "无法使用当前阵容开赛。"
		return
	var data: Variant = JSON.parse_string(body.get_string_from_utf8())
	if result != HTTPRequest.RESULT_SUCCESS or code != 200 or not data is Dictionary:
		_status.text = "无法读取球员卡，请检查比赛服务器。"
		return
	_cards = data["cards"]
	_positioning = data.get("positioning", {})
	_modes.select(int(data["teamSize"]) - 1 + (2 if data["watch"] else 0))
	_update_tactics_visibility(_modes.selected)
	var tactics: Dictionary = data.get("homeTactics", {})
	_select_tactic(_attack_support, tactics.get("attackSupport", "Wide"))
	_select_tactic(_defense, tactics.get("defense", "Pressure"))
	_show_cards()
	_active_match = data["active"]
	if _active_match:
		_play.text = "返回赛场"
		_play.disabled = false
		_modes.disabled = true
		_attack_support.disabled = true
		_defense.disabled = true
		_cards_grid.hide()
		_formation_panel.hide()
		_status.text = "本场阵容已确定。"
	else:
		_status.text = "" if not _chosen.is_empty() else "没有可选球员卡。"


func _enter_match() -> void:
	var game := MATCH_SCENE.instantiate()
	game.api.token = token
	var tree := get_tree()
	tree.root.add_child(game)
	tree.current_scene = game
	queue_free()
