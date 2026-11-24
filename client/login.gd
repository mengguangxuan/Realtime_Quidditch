extends Control

const LOBBY_SCENE := preload("res://lobby.tscn")
const PITCH := preload("res://assets/pitch.glb")
const INK := Color("#f1eee0")
const MUTED := Color("#b7c9b6")
const GOLD := Color("#e5c77a")

var _request := HTTPRequest.new()
var _username := LineEdit.new()
var _password := LineEdit.new()
var _login := Button.new()
var _register := Button.new()
var _message := Label.new()
var _registering := false
var _busy := false


func _ready() -> void:
	_create_backdrop()
	var center := CenterContainer.new()
	add_child(center)
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var panel := PanelContainer.new()
	panel.custom_minimum_size.x = 428
	panel.add_theme_stylebox_override("panel", _style(Color("#14241f"), Color("#4d6154"), 30))
	center.add_child(panel)
	var form := VBoxContainer.new()
	form.add_theme_constant_override("separation", 12)
	panel.add_child(form)
	_add_label(form, "实况魁地奇", 31, GOLD)
	_add_label(form, "登录比赛账号", 18, INK)
	_add_label(form, "账号", 15, MUTED)
	_add_field(form, _username, "3–24 位字母、数字或下划线", 24)
	_add_label(form, "密码", 15, MUTED)
	_add_field(form, _password, "至少 8 个字符", 128)
	_password.secret = true
	_username.text_submitted.connect(func(_value: String) -> void: _password.grab_focus())
	_password.text_submitted.connect(func(_value: String) -> void: _submit(false))
	var buttons := HBoxContainer.new()
	buttons.add_theme_constant_override("separation", 10)
	form.add_child(buttons)
	_add_button(buttons, _login, "登录", true)
	_add_button(buttons, _register, "注册账号", false)
	_message.custom_minimum_size.y = 25
	_message.add_theme_color_override("font_color", MUTED)
	_message.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	form.add_child(_message)
	_login.pressed.connect(func() -> void: _submit(false))
	_register.pressed.connect(func() -> void: _submit(true))
	add_child(_request)
	_request.timeout = 8
	_request.request_completed.connect(_completed)
	_username.grab_focus()


func _create_backdrop() -> void:
	add_child(PITCH.instantiate())
	var environment := WorldEnvironment.new()
	environment.environment = Environment.new()
	environment.environment.background_mode = Environment.BG_COLOR
	environment.environment.background_color = Color("#345448")
	environment.environment.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	environment.environment.ambient_light_color = Color("#bacabe")
	environment.environment.ambient_light_energy = 0.55
	add_child(environment)
	var light := DirectionalLight3D.new()
	light.rotation_degrees = Vector3(-42, -40, 0)
	light.light_color = Color("#ffe1ad")
	add_child(light)
	var camera := Camera3D.new()
	add_child(camera)
	camera.position = Vector3(-49, 24, 34)
	camera.look_at(Vector3(3, 4, 0))
	camera.current = true
	var shade := ColorRect.new()
	shade.color = Color(0.025, 0.075, 0.062, 0.48)
	shade.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(shade)
	shade.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)


func _style(fill: Color, border: Color, padding: int) -> StyleBoxFlat:
	var style := StyleBoxFlat.new()
	style.bg_color = fill
	style.border_color = border
	style.set_border_width_all(1)
	style.set_corner_radius_all(8)
	style.content_margin_left = padding
	style.content_margin_top = padding
	style.content_margin_right = padding
	style.content_margin_bottom = padding
	return style


func _add_label(parent: Container, caption: String, font_size: int, color: Color) -> void:
	var label := Label.new()
	label.text = caption
	label.add_theme_font_size_override("font_size", font_size)
	label.add_theme_color_override("font_color", color)
	parent.add_child(label)


func _add_field(parent: Container, field: LineEdit, placeholder: String, limit: int) -> void:
	field.placeholder_text = placeholder
	field.max_length = limit
	field.custom_minimum_size.y = 46
	field.add_theme_stylebox_override("normal", _style(Color("#101c17"), Color("#4d6154"), 10))
	field.add_theme_stylebox_override("focus", _style(Color("#101c17"), GOLD, 10))
	field.add_theme_color_override("font_color", INK)
	parent.add_child(field)


func _add_button(parent: Container, button: Button, caption: String, primary: bool) -> void:
	button.text = caption
	button.custom_minimum_size.y = 44
	button.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	button.add_theme_stylebox_override("normal", _style(GOLD if primary else Color("#23382d"), GOLD, 8))
	button.add_theme_stylebox_override("hover", _style(Color("#f2da98") if primary else Color("#345142"), GOLD, 8))
	button.add_theme_color_override("font_color", Color("#18221a") if primary else INK)
	button.add_theme_color_override("font_hover_color", Color("#18221a") if primary else INK)
	parent.add_child(button)


func _submit(register: bool) -> void:
	if _busy:
		return
	_registering = register
	_set_busy(true)
	_message.text = "正在注册…" if register else "正在登录…"
	var endpoint := "/auth/register" if register else "/auth/login"
	var result := _request.request(MatchApi.backend_origin() + endpoint, ["Content-Type: application/json"],
		HTTPClient.METHOD_POST, JSON.stringify({"username": _username.text, "password": _password.text}))
	if result != OK:
		_set_busy(false)
		_message.text = "无法连接比赛服务器。"


func _set_busy(busy: bool) -> void:
	_busy = busy
	_login.disabled = busy
	_register.disabled = busy
	_username.editable = not busy
	_password.editable = not busy


func _completed(result: int, code: int, _headers: PackedStringArray, body: PackedByteArray) -> void:
	_set_busy(false)
	if result != HTTPRequest.RESULT_SUCCESS:
		_message.text = "无法连接比赛服务器，请重试。"
		return
	var payload: Variant = JSON.parse_string(body.get_string_from_utf8())
	if _registering and code == 201:
		_message.text = "账号已创建，请登录。"
		_password.grab_focus()
	elif not _registering and code == 200 and payload is Dictionary and not str(payload.get("token", "")).is_empty():
		var lobby := LOBBY_SCENE.instantiate()
		lobby.token = payload["token"]
		var tree := get_tree()
		tree.root.add_child(lobby)
		tree.current_scene = lobby
		queue_free()
	elif code == 401:
		_message.text = "账号或密码错误。"
	elif code == 409:
		_message.text = "这个账号已存在，请直接登录。"
	elif code == 400:
		_message.text = "请检查账号格式和密码长度。"
	else:
		_message.text = "服务器暂时无法处理请求，请重试。"
