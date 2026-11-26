extends RefCounted

const FALLBACK := preload("res://assets/rider.glb")
const BROOM := preload("res://assets/riders/broom.glb")

static func model_path(athlete_id: int) -> String:
	var model_id := 10 if athlete_id == 11 else athlete_id
	return "res://assets/riders/athlete_%d.glb" % model_id


static func create(athlete_id: int) -> Node3D:
	var path := model_path(athlete_id)
	if not ResourceLoader.exists(path, "PackedScene"):
		return FALLBACK.instantiate()
	var model := load(path) as PackedScene
	var rider := Node3D.new()
	rider.add_child(model.instantiate())
	rider.add_child(BROOM.instantiate())
	return rider
