extends Control

const MATCH_SCENE := preload("res://match.tscn")
const RiderAssets := preload("res://rider_assets.gd")
const GOLD := Color("#e5c77a")
const INK := Color("#f1eee0")

var token := ""
var _request := HTTPRequest.new()
var _modes := OptionButton.new()
var _cards_grid := HBoxContainer.new()
var _opponents_grid := HBoxContainer.new()
var _detail := Label.new()
var _status := Label.new()
var _play := Button.new()
var _preview := Node3D.new()
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
	form.add_theme_constant_override("separation", 16)
	layout.add_child(form)
	_label(form, "比赛准备", 35, GOLD)
	_label(form, "对战模式", 18, INK)
	for option: String in ["1 对 1  ·  人机对战", "2 对 2  ·  人机对战", "1 对 1  ·  电脑观战", "2 对 2  ·  电脑观战"]:
		_modes.add_item(option)
	_modes.custom_minimum_size.y = 46
	form.add_child(_modes)
	_label(form, "选择格兰芬多首发", 18, INK)
	form.add_child(_cards_grid)
	_label(form, "查看斯莱特林球员", 16, INK)
	form.add_child(_opponents_grid)
	_detail.custom_minimum_size.y = 142
	_detail.add_theme_constant_override("line_spacing", 6)
	form.add_child(_detail)
	_play.text = "开始比赛"
	_play.custom_minimum_size.y = 46
	_play.disabled = true
	_play.pressed.connect(_start)
	form.add_child(_play)
	_status.text = "正在读取球员卡…"
	form.add_child(_status)
	_create_preview(layout)
	add_child(_request)
	_request.timeout = 8
	_request.request_completed.connect(_completed)
	_request.request(MatchApi.backend_origin() + "/matches/lobby", _headers())


func _label(parent: Control, caption: String, size: int, color: Color) -> void:
	var label := Label.new()
	label.text = caption
	label.add_theme_font_size_override("font_size", size)
	label.add_theme_color_override("font_color", color)
	parent.add_child(label)


func _create_preview(parent: Control) -> void:
	var container := SubViewportContainer.new()
	container.size_flags_horizontal = Control.SIZE_EXPAND_FILL
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
	_play.disabled = false


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
	if _starting or _chosen.is_empty():
		return
	_starting = true
	_play.disabled = true
	_status.text = "正在开赛…"
	var payload := {"athlete": int(_chosen["id"]), "teamSize": _modes.selected % 2 + 1,
		"watch": _modes.selected >= 2}
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
	_modes.select(int(data["teamSize"]) - 1 + (2 if data["watch"] else 0))
	_show_cards()
	_active_match = data["active"]
	if _active_match:
		_play.text = "返回赛场"
		_play.disabled = false
		_modes.disabled = true
		_cards_grid.hide()
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
