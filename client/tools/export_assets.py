"""Run with Blender's Python to rebuild the two game assets from the source scenes."""

from math import pi
from pathlib import Path

import bpy
from mathutils import Matrix

project = Path(__file__).resolve().parents[2]
assets = project / "client" / "assets"
assets.mkdir(parents=True, exist_ok=True)


def export_selected(name):
    bpy.ops.export_scene.gltf(
        filepath=str(assets / name),
        export_format="GLB",
        use_selection=True,
        export_animations=False,
    )


bpy.ops.wm.open_mainfile(filepath=str(project / "Quidditch_Pitch.blend"))
bpy.ops.object.select_all(action="DESELECT")
for obj in bpy.context.scene.objects:
    if obj.type in {"MESH", "CURVE"} and not obj.hide_render:
        obj.select_set(True)
export_selected("pitch.glb")

bpy.ops.wm.open_mainfile(filepath=str(project / "Quidditch_Harry_Takeoff.blend"))
bpy.context.scene.frame_set(148)
source = bpy.data.collections["07 · Harry and flying broom"]
rig = next(obj for obj in source.objects if obj.type == "ARMATURE")
broom = bpy.data.objects["Flying broom · animated prop"]
depsgraph = bpy.context.evaluated_depsgraph_get()
origin = broom.evaluated_get(depsgraph).matrix_world.to_translation()
to_local = Matrix.Rotation(-pi / 2, 4, "Z") @ Matrix.Translation(-origin)
bpy.ops.object.select_all(action="DESELECT")

for obj in source.objects:
    if obj.type != "MESH":
        continue
    skinned = any(mod.type == "ARMATURE" and mod.object == rig for mod in obj.modifiers)
    if not (skinned or obj.parent == broom):
        continue
    evaluated = obj.evaluated_get(depsgraph)
    mesh = bpy.data.meshes.new_from_object(evaluated, preserve_all_data_layers=True, depsgraph=depsgraph)
    copy = bpy.data.objects.new(obj.name + " flight", mesh)
    bpy.context.scene.collection.objects.link(copy)
    copy.matrix_world = to_local @ evaluated.matrix_world
    copy.select_set(True)
export_selected("rider.glb")
