extends SceneTree

var game


func _initialize() -> void:
	_run.call_deferred()


func _run() -> void:
	game = load("res://match.tscn").instantiate()
	game.read_keyboard = false
	root.add_child(game)
	if not await _until(func() -> bool: return not game.current_state.is_empty(), 6.0):
		_fail("No Scala snapshot reached Godot")
		return
	game.api.start_match()
	if not await _until(func() -> bool: return game.current_state["phase"].has("InPlay"), 4.0):
		_fail("Godot could not start the match")
		return
	if not await _fly_to(Vector3(0, 0, 1.5)):
		_fail("Could not move the rider to the grounded Quaffle")
		return
	game.api.control({"Catch": {"target": "Quaffle"}})
	if not await _until(func() -> bool: return int(game._quaffle().get("HeldBy", {}).get("athlete", -1)) == 1, 3.0):
		_fail("Catch command did not give the Godot rider possession")
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
	game._target_ring = 2
	game.shoot()
	if not await _until(func() -> bool: return int(game.current_state["score"]["home"]) == 10, 4.0):
		_fail("Godot shot did not score in Scala")
		return
	print("Godot ↔ Scala passed: imported scene, start, fly, catch, shoot, scoreboard")
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
