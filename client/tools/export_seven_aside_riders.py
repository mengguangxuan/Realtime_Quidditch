"""Convert the local team models to lightweight mounted riders for Godot."""

from pathlib import Path
import sys

import bpy
import numpy as np
from mathutils import Matrix, Vector

sys.path.insert(0, str(Path(__file__).resolve().parent))
from rig_generated_characters import add_pose_preview, build_rig


PROJECT = Path(__file__).resolve().parents[2]
GRYFFINDOR = {
    5: "Ginny_Weasley",
    6: "Katie_Bell",
    7: "Demelza_Robins",
    8: "Jimmy_Peakes",
    9: "Ritchie_Coote",
    10: "Fred_George_Weasley_shared",
}
BARCELONA = {
    12: "Gabriel_Jesus",
    13: "Joan_Garcia",
    14: "Jules_Kounde",
    15: "Karim_Adeyemi",
    16: "Lamine_Yamal",
    17: "Pau_Cubarsi",
    18: "Raphinha",
}
TRIANGLE_BUDGET = 32_000
TEXTURE_LIMIT = 1280


def export_rider(source, destination):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(source))
    (mesh,) = [obj for obj in bpy.context.scene.objects if obj.type == "MESH"]
    mesh.data.transform(mesh.matrix_world)
    mesh.parent = None
    mesh.matrix_world = Matrix.Identity(4)
    height = max(vertex.co.z for vertex in mesh.data.vertices) - min(
        vertex.co.z for vertex in mesh.data.vertices
    )
    mesh.data.transform(Matrix.Scale(1.62 / height, 4))
    decimate = mesh.modifiers.new("Game mesh budget", "DECIMATE")
    decimate.ratio = min(1.0, TRIANGLE_BUDGET / len(mesh.data.polygons))
    bpy.context.view_layer.objects.active = mesh
    bpy.ops.object.modifier_apply(modifier=decimate.name)

    coordinates = np.empty(len(mesh.data.vertices) * 3, dtype=np.float32)
    mesh.data.vertices.foreach_get("co", coordinates)
    x, _, z = coordinates.reshape(-1, 3).T
    heights = [
        float(np.median(z[sign * x >= np.percentile(sign * x, 96)])) / 1.62
        for sign in (1, -1)
    ]
    rig = build_rig(mesh, heights)
    add_pose_preview(rig)
    bpy.context.scene.frame_set(30)
    graph = bpy.context.evaluated_depsgraph_get()
    posed = bpy.data.meshes.new_from_object(mesh.evaluated_get(graph), depsgraph=graph)
    rider = bpy.data.objects.new(source.stem, posed)
    bpy.context.collection.objects.link(rider)
    rider.matrix_world = mesh.matrix_world.copy()
    rider.location += Vector((0, 0, 0.13)) - rig.matrix_world @ rig.pose.bones["hips"].head

    for image in bpy.data.images:
        width, height = image.size
        longest = max(width, height)
        if longest > TEXTURE_LIMIT:
            image.scale(round(width * TEXTURE_LIMIT / longest), round(height * TEXTURE_LIMIT / longest))
            image.pack()
    bpy.ops.object.select_all(action="DESELECT")
    rider.select_set(True)
    bpy.context.view_layer.objects.active = rider
    bpy.ops.export_scene.gltf(
        filepath=str(destination), export_format="GLB", use_selection=True, export_animations=False,
    )


def main():
    for team, models in (("Gryffindor", GRYFFINDOR), ("Barcelona", BARCELONA)):
        directory = PROJECT / "generated" / team
        for athlete, name in models.items():
            shared = name.endswith("_shared")
            (source,) = directory.glob(f"{name}.glb" if shared else f"{name}_*.glb")
            destination = PROJECT / "client" / "assets" / "riders" / f"athlete_{athlete}.glb"
            export_rider(source, destination)
            print(f"Exported {athlete}: {destination.stat().st_size} bytes", flush=True)


if __name__ == "__main__":
    main()
