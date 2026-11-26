shader_type spatial;

uniform sampler2D original_texture : source_color, filter_linear_mipmap, hint_default_white;
uniform vec4 kit_color : source_color;
uniform float mesh_floor;
uniform float mesh_height;
uniform bool keeper;

varying vec3 mounted_position;

void vertex() {
	mounted_position = VERTEX;
}

void fragment() {
	vec3 original = texture(original_texture, UV).rgb;
	float height = (mounted_position.y - mesh_floor) / mesh_height;
	float shoulders = smoothstep(0.58, 0.64, height) * (1.0 - smoothstep(0.78, 0.82, height));
	float front = smoothstep(0.09, 0.21, mounted_position.z);
	float side = smoothstep(0.11, 0.20, abs(mounted_position.x));
	float fabric = shoulders * max(1.0 - front, side);
	float shade = 0.63 + 0.37 * dot(original, vec3(0.2126, 0.7152, 0.0722));
	vec3 cloth = kit_color.rgb * shade;
	if (keeper) {
		float panel = smoothstep(0.64, 0.69, height) * smoothstep(0.045, 0.10, abs(mounted_position.x));
		cloth = mix(cloth, vec3(0.95, 0.96, 0.98) * shade, panel);
	}
	ALBEDO = mix(original, cloth, fabric * 0.96);
	ROUGHNESS = 0.88;
}
