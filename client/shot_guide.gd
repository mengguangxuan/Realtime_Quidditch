extends Node3D

const RELEASE_OFFSET := 0.65
const POWER := 1.0
const PITCH_LIMIT := deg_to_rad(80.0)
const AIM_SPEED := deg_to_rad(34.0)

var _yaw := 0.0
var _pitch := 0.0


func _ready() -> void:
	var material := StandardMaterial3D.new()
	material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	material.albedo_color = Color(0.46, 1.0, 0.69)
	_segment(0.055, 0.055, 2.7, 1.35, material)
	_segment(0.0, 0.22, 0.58, 2.99, material)
	hide()


func set_direction(value: Vector3) -> void:
	if value.is_zero_approx():
		return
	var unit := value.normalized()
	_yaw = atan2(unit.z, unit.x)
	_pitch = clampf(asin(unit.y), -PITCH_LIMIT, PITCH_LIMIT)


func rotate_aim(horizontal: float, vertical: float, delta: float) -> void:
	_rotate(horizontal * AIM_SPEED * delta, vertical * AIM_SPEED * delta)


func aim_with_mouse(relative: Vector2) -> void:
	_rotate(relative.x * 0.0025, -relative.y * 0.0025)


func _rotate(yaw_change: float, pitch_change: float) -> void:
	_yaw = wrapf(_yaw + yaw_change, -PI, PI)
	_pitch = clampf(_pitch + pitch_change, -PITCH_LIMIT, PITCH_LIMIT)


func direction() -> Vector3:
	return Vector3(cos(_yaw) * cos(_pitch), sin(_pitch), sin(_yaw) * cos(_pitch))


func show_tangent(rider_position: Vector3, rider_velocity: Vector3, throw_power_rating: float) -> void:
	var aim := direction()
	# Mirrors the backend's unperturbed release velocity at the current shot power.
	var release_velocity := rider_velocity + aim * (5.0 + POWER * (17.0 + throw_power_rating * 0.15))
	visible = not release_velocity.is_zero_approx()
	if visible:
		position = rider_position + aim * RELEASE_OFFSET
		quaternion = Quaternion(Vector3.UP, release_velocity.normalized())


func _segment(top: float, bottom: float, height: float, center: float, material: Material) -> void:
	var cylinder := CylinderMesh.new()
	cylinder.top_radius = top
	cylinder.bottom_radius = bottom
	cylinder.height = height
	cylinder.radial_segments = 12
	var part := MeshInstance3D.new()
	part.mesh = cylinder
	part.material_override = material
	part.position.y = center
	add_child(part)
