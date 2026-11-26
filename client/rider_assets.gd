extends RefCounted

const FALLBACK := preload("res://assets/rider.glb")
const BROOM := preload("res://assets/riders/broom.glb")
const KIT := preload("res://rider_kit.gdshader")
const HOME_KIT := Color("#cf233c")
const AWAY_KIT := Color("#25aaff")

static func model_path(athlete_id: int) -> String:
	var model_id := 10 if athlete_id == 11 else athlete_id
	return "res://assets/riders/athlete_%d.glb" % model_id


static func create(athlete_id: int, kit_color: Color = Color.TRANSPARENT, keeper: bool = false) -> Node3D:
	var path := model_path(athlete_id)
	if not ResourceLoader.exists(path, "PackedScene"):
		return FALLBACK.instantiate()
	var model := load(path) as PackedScene
	var rider := Node3D.new()
	var body := model.instantiate()
	rider.add_child(body)
	rider.add_child(BROOM.instantiate())
	if kit_color.a > 0:
		var meshes := body.find_children("*", "MeshInstance3D", true, false)
		if body is MeshInstance3D:
			meshes.append(body)
		for mesh: MeshInstance3D in meshes:
			var bounds := mesh.get_aabb()
			for surface in mesh.mesh.get_surface_count():
				var original := mesh.get_active_material(surface) as BaseMaterial3D
				if original == null or original.albedo_texture == null:
					continue
				var material := ShaderMaterial.new()
				material.shader = KIT
				material.set_shader_parameter("original_texture", original.albedo_texture)
				material.set_shader_parameter("kit_color", kit_color)
				material.set_shader_parameter("mesh_floor", bounds.position.y)
				material.set_shader_parameter("mesh_height", maxf(0.01, bounds.size.y))
				material.set_shader_parameter("keeper", keeper)
				mesh.set_surface_override_material(surface, material)
	return rider
