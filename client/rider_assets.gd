extends RefCounted

const FALLBACK := preload("res://assets/rider.glb")
const BROOM := preload("res://assets/riders/broom.glb")
const MODELS := {
	1: preload("res://assets/riders/athlete_1.glb"),
	2: preload("res://assets/riders/athlete_2.glb"),
	3: preload("res://assets/riders/athlete_3.glb"),
	4: preload("res://assets/riders/athlete_4.glb"),
}


static func create(athlete_id: int) -> Node3D:
	if not MODELS.has(athlete_id):
		return FALLBACK.instantiate()
	var rider := Node3D.new()
	rider.add_child(MODELS[athlete_id].instantiate())
	rider.add_child(BROOM.instantiate())
	return rider
