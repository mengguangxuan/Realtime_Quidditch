extends SceneTree

var game


func _initialize() -> void:
	_run.call_deferred()


func _run() -> void:
	var request := HTTPRequest.new()
	request.timeout = 5
	root.add_child(request)
	request.request(MatchApi.backend_origin() + "/matches/demo")
	var anonymous: Array = await request.request_completed
	if anonymous[1] != 401:
		_fail("The server allowed an unauthenticated match request")
		return
	request.request(MatchApi.backend_origin() + "/matches/demo/start", [], HTTPClient.METHOD_POST)
	anonymous = await request.request_completed
	request.queue_free()
	if anonymous[1] != 401:
		_fail("The server allowed an unauthenticated match start")
		return
	var login = load("res://login.tscn").instantiate()
	root.add_child(login)
	current_scene = login
	login._username.text = "smoke_player"
	login._password.text = "smoke password 42"
	login._submit(true)
	if not await _until(func() -> bool: return login._message.text.contains("账号已创建"), 6.0):
		_fail("Registration did not create an account")
		return
	login._password.text = "incorrect password"
	login._submit(false)
	if not await _until(func() -> bool: return login._message.text.contains("账号或密码错误"), 6.0):
		_fail("The login did not reject an incorrect password")
		return
	login._password.text = "smoke password 42"
	var login_id: int = login.get_instance_id()
	login._submit(false)
	if not await _until(func() -> bool: return current_scene.get_instance_id() != login_id, 6.0):
		_fail("A valid account could not enter the lobby")
		return
	var lobby = current_scene
	if not await _until(func() -> bool: return not lobby._chosen.is_empty(), 6.0):
		_fail("The lobby did not load athlete cards")
		return
	var selected := 1
	if lobby._modes.selected >= 2:
		for card: Dictionary in lobby._cards:
			if int(card["id"]) == 3:
				lobby._choose(card)
				selected = 3
	var lobby_id: int = lobby.get_instance_id()
	lobby._start()
	if not await _until(func() -> bool: return current_scene.get_instance_id() != lobby_id, 6.0):
		_fail("The configured match could not start")
		return
	game = current_scene
	game.read_keyboard = false
	if not await _until(func() -> bool: return not game.current_state.is_empty(), 6.0):
		_fail("No Scala snapshot reached Godot")
		return
	var visual := Node3D.new()
	game._update_rider_visual(visual, PI / 2, Vector3.LEFT * 8, 1.0)
	var reverse_ok := absf(absf(visual.rotation.y) - PI) < 0.01
	game._update_rider_visual(visual, PI / 2, Vector3.BACK * 8, 1.0)
	var strafe_ok := absf(angle_difference(visual.rotation.y, -PI / 2)) < 0.01
	game._update_rider_visual(visual, PI / 2, Vector3.ZERO, 1.0)
	var idle_ok := absf(angle_difference(visual.rotation.y, 0)) < 0.01
	visual.free()
	if not (reverse_ok and strafe_ok and idle_ok):
		_fail("Rider visual did not face travel direction and return to its heading when stopped")
		return
	if not await _until(func() -> bool: return game.current_state["phase"].has("InPlay"), 4.0):
		_fail("Godot could not start the match")
		return
	if int(game._selected_id) != selected:
		_fail("The server did not assign the selected athlete card")
		return
	for id: String in game._rider_visuals:
		var model: Node3D = game._rider_visuals[id].get_child(0)
		if model.scene_file_path != "res://assets/riders/athlete_%s.glb" % id:
			_fail("A rider did not use its own Blender model")
			return
		var meshes := model.find_children("*", "MeshInstance3D", true, false)
		if model is MeshInstance3D:
			meshes.append(model)
		if not meshes.any(func(mesh: MeshInstance3D) -> bool:
			var material := mesh.get_active_material(0) as BaseMaterial3D
			return material != null and material.albedo_texture != null):
			_fail("A rider was imported without its texture")
			return
	if not await _check_match_owner():
		return
	if game._spectating:
		await _check_spectator()
		return
	if not await _fly_to(Vector3(0, 0, 1.5)):
		_fail("Could not move the rider to the grounded Quaffle")
		return
	game.api.control({"Catch": {"target": "Quaffle"}})
	if not await _until(func() -> bool: return int(game._quaffle().get("HeldBy", {}).get("athlete", -1)) == 1, 3.0):
		_fail("Catch command did not give the Godot rider possession")
		return
	if game._roster.size() == 4:
		await _check_team_play()
		return
	if not await _fly_to(Vector3(36, 7.1, 10)):
		_fail("Could not move to shooting distance")
		return
	game.api.flight({"forward": 0.08, "strafe": 0, "climb": 0, "sprint": false})
	if not await _until(func() -> bool: return game.current_state["athletes"]["1"]["flight"]["facing"]["x"] > 0.96, 2.0):
		_fail("Could not face the target before shooting")
		return
	game.api.flight({"forward": 0, "strafe": 0, "climb": 0, "sprint": false})
	if not await _until(func() -> bool: return _vector(game.current_state["athletes"]["1"]["flight"]["velocity"]["value"]).length() < 0.8, 2.0):
		_fail("Could not settle the rider before shooting")
		return
	game.shot_guide.set_direction(Vector3.RIGHT)
	game.shot_guide.show_tangent(Vector3.ZERO, Vector3(0, 0, 10), 80)
	if game.shot_guide.basis.y.dot(Vector3(34, 0, 10).normalized()) < 0.9999:
		_fail("Shot arrow did not follow release velocity")
		return
	game.shot_guide.aim_with_mouse(Vector2(200, -55))
	var desired: Vector3 = game.shot_guide.direction()
	game._aiming = true
	game._update_camera(1.0)
	if (desired.z <= 0 or desired.y <= 0 or game._camera.fov > 55.0
			or (-game._camera.global_basis.z).dot(desired) < 0.95):
		_fail("Shoulder camera did not follow the mouse aim")
		return
	game._aiming = false
	game._update_camera(1.0)
	if game._camera.fov < 70.0:
		_fail("Camera did not return to its wide view")
		return
	var position := _vector(game.current_state["athletes"]["1"]["flight"]["position"]["value"])
	var target := Vector3(44, 7.1, 10.45) - position
	game.shot_guide.set_direction(Vector3(target.x, target.z, -target.y))
	game.shoot()
	if not await _until(func() -> bool: return int(game.current_state["score"]["home"]) == 10, 4.0):
		_fail("Godot shot did not score in Scala")
		return
	print("Godot ↔ Scala passed: imported scene, start, fly, catch, shoot, scoreboard")
	quit(0)


func _check_match_owner() -> bool:
	var request := HTTPRequest.new()
	request.timeout = 5
	root.add_child(request)
	var origin := MatchApi.backend_origin()
	var headers := ["Content-Type: application/json"]
	var credentials := JSON.stringify({"username": "smoke_other", "password": "other smoke password"})
	request.request(origin + "/auth/register", headers, HTTPClient.METHOD_POST, credentials)
	var response: Array = await request.request_completed
	if response[1] != 201:
		request.queue_free()
		_fail("Could not register another account")
		return false
	request.request(origin + "/auth/login", headers, HTTPClient.METHOD_POST, credentials)
	response = await request.request_completed
	if response[1] != 200:
		request.queue_free()
		_fail("Could not authenticate the other account")
		return false
	var payload: Dictionary = JSON.parse_string(response[3].get_string_from_utf8())
	headers.append("Authorization: Bearer " + str(payload["token"]))
	request.request(origin + "/matches/demo/controls", headers, HTTPClient.METHOD_POST,
		JSON.stringify({"player": "home", "control": {"Charge": {}}}))
	response = await request.request_completed
	if response[1] != 403:
		request.queue_free()
		_fail("The other account could control the active match")
		return false
	request.request(origin + "/auth/logout", headers, HTTPClient.METHOD_POST)
	response = await request.request_completed
	request.request(origin + "/matches/demo", headers)
	response = await request.request_completed
	request.queue_free()
	if response[1] != 401:
		_fail("Logout did not revoke the token")
		return false
	return true


func _check_team_play() -> void:
	if game._teammate_id != "3" or not game._roster.has("4"):
		_fail("The four-player lineup was not loaded")
		return
	game.pass_to_teammate()
	game.switch_teammate()
	if not await _until(func() -> bool: return game._selected_id == "3", 2.0):
		_fail("Tab control did not transfer to Ron")
		return
	for attempt in 80:
		if int(game._quaffle().get("HeldBy", {}).get("athlete", -1)) == 3:
			game.api.flight({"forward": 0, "strafe": 0, "climb": 0, "sprint": false})
			game.shoot()
			if not await _until(func() -> bool: return game._quaffle().has("Free"), 2.0):
				_fail("Ron could not shoot after accepting the pass")
				return
			print("Godot ↔ Scala passed: four riders, pass, control switch, teammate catch and shot")
			quit(0)
			return
		var motion: Dictionary = game._quaffle().get("Free", {}).get("motion", {})
		if not motion.is_empty():
			var receiver: Dictionary = game.current_state["athletes"]["3"]["flight"]
			var relative := _vector(motion["position"]["value"]) - _vector(receiver["position"]["value"])
			var lead := _vector(motion["velocity"]["value"]) * 0.18
			var adjust := (relative + lead) * 0.35 - _vector(receiver["velocity"]["value"]) * 0.10
			game.api.flight({
				"forward": clampf(adjust.x, -1.0, 1.0), "strafe": clampf(adjust.y, -1.0, 1.0),
				"climb": clampf(adjust.z, -1.0, 1.0), "sprint": false
			})
		game.api.control({"Catch": {"target": "Quaffle"}})
		await create_timer(0.10).timeout
	_fail("The passed Quaffle was not caught by Ron")


func _check_spectator() -> void:
	if not await _until(func() -> bool: return game._quaffle().has("HeldBy"), 8.0):
		_fail("Neither computer caught the Quaffle")
		return
	var camera: Camera3D = game._camera
	for id: String in game._roster:
		var rider: Node3D = game._roster[id]
		if not camera.get_frustum().all(func(plane: Plane) -> bool:
			return not plane.is_point_over(rider.position)
		):
			_fail("Spectator camera did not frame both riders")
			return
	if game._controls.visible or game._home_holds_ball or not game._score.text.contains("绿队电脑"):
		_fail("Spectator HUD did not switch to computer teams")
		return
	print("Godot ↔ Scala passed: CPU vs CPU, autonomous catch, spectator camera and HUD")
	quit(0)


func _fly_to(target: Vector3) -> bool:
	for step in 95:
		var flight: Dictionary = game.current_state["athletes"]["1"]["flight"]
		var offset := target - _vector(flight["position"]["value"])
		var velocity := _vector(flight["velocity"]["value"])
		var adjust := offset * 0.24 - velocity * 0.075
		game.api.flight({
			"forward": clampf(adjust.x, -1, 1), "strafe": clampf(adjust.y, -1, 1),
			"climb": clampf(adjust.z, -1, 1), "sprint": false
		})
		if offset.length() < 0.38 and velocity.length() < 1.0:
			game.api.flight({"forward": 0, "strafe": 0, "climb": 0, "sprint": false})
			return true
		await create_timer(0.08).timeout
	return false


func _until(condition: Callable, seconds: float) -> bool:
	var deadline := Time.get_ticks_msec() + int(seconds * 1000)
	while Time.get_ticks_msec() < deadline:
		if condition.call():
			return true
		await create_timer(0.05).timeout
	return false


func _vector(value: Dictionary) -> Vector3:
	return Vector3(value["x"], value["y"], value["z"])


func _fail(message: String) -> void:
	push_error(message)
	quit(1)
