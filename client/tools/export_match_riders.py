"""Bake the mounted pose from the four Blender rigs for Godot."""

from pathlib import Path

import bpy
from mathutils import Vector


PROJECT = Path(__file__).resolve().parents[2]
ASSETS = PROJECT / "client" / "assets" / "riders"
RIGS = PROJECT / "generated" / "rigged"
CHARACTERS = ("Harry_Potter", "Draco_Malfoy", "Ron_Weasley", "Vincent_Crabbe")


def export(path):
    bpy.ops.export_scene.gltf(
        filepath=str(path),
        export_format="GLB",
        use_selection=True,
        export_animations=False,
    )


ASSETS.mkdir(exist_ok=True)
for athlete_id, character in enumerate(CHARACTERS, start=1):
    bpy.ops.wm.open_mainfile(filepath=str(RIGS / f"{character}_Rigged.blend"))
    bpy.context.scene.frame_set(30)
    rig = bpy.data.objects["Character Rig"]
    body = next(obj for obj in rig.children if obj.type == "MESH")
    graph = bpy.context.evaluated_depsgraph_get()
    posed = bpy.data.meshes.new_from_object(body.evaluated_get(graph), depsgraph=graph)
    rider = bpy.data.objects.new(character, posed)
    bpy.context.collection.objects.link(rider)
    rider.matrix_world = body.matrix_world.copy()
    seat = rig.matrix_world @ rig.pose.bones["hips"].head
    rider.location -= seat - Vector((0, 0, 0.13))
    bpy.ops.object.select_all(action="DESELECT")
    bpy.context.view_layer.objects.active = rider
    rider.select_set(True)
    decimate = rider.modifiers.new("Game mesh budget", "DECIMATE")
    decimate.ratio = 0.18
    bpy.ops.object.modifier_apply(modifier=decimate.name)
    for image in bpy.data.images:
        width, height = image.size
        largest = max(width, height)
        if largest > 2048:
            image.scale(round(width * 2048 / largest), round(height * 2048 / largest))
            image.pack()
    export(ASSETS / f"athlete_{athlete_id}.glb")
    print(f"{character}: {len(rider.data.polygons)} polygons")

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=str(ASSETS.parent / "rider.glb"))
bpy.ops.object.select_all(action="DESELECT")
for item in bpy.context.scene.objects:
    if item.type == "MESH" and item.name.startswith((
        "Broom shaft", "Bound bristle", "Gripping band", "Individual reed", "Metal collar"
    )):
        item.select_set(True)
export(ASSETS / "broom.glb")
