"""Build first-pass Blender rigs from generated character GLBs."""

from pathlib import Path

import bpy
import numpy as np
from mathutils import Matrix, Vector


PROJECT = Path(__file__).resolve().parents[2]
SOURCE = PROJECT / "generated"
OUTPUT = SOURCE / "rigged"
CHARACTERS = {
    "Harry_Potter": (0.72, 0.70),
    "Ron_Weasley": (0.79, 0.78),
    "Draco_Malfoy": (0.64, 0.62),
    "Vincent_Crabbe": (0.64, 0.64),
}


def smoothstep(low, high, values):
    fraction = np.clip((values - low) / (high - low), 0, 1)
    return fraction * fraction * (3 - 2 * fraction)


def build_rig(mesh, hand_heights):
    bpy.context.view_layer.objects.active = mesh
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    coordinates = np.empty(len(mesh.data.vertices) * 3, dtype=np.float32)
    mesh.data.vertices.foreach_get("co", coordinates)
    coordinates = coordinates.reshape(-1, 3)
    ground = float(coordinates[:, 2].min())
    height = float(coordinates[:, 2].max()) - ground
    mesh.data.transform(Matrix.Translation((0, 0, -ground)))
    coordinates[:, 2] -= ground
    coordinates /= height
    x, y, z = coordinates.T
    armature = bpy.data.armatures.new("Character Skeleton")
    rig = bpy.data.objects.new("Character Rig", armature)
    bpy.context.collection.objects.link(rig)
    rig.show_in_front = True
    armature.display_type = "STICK"
    bpy.ops.object.select_all(action="DESELECT")
    rig.select_set(True)
    bpy.context.view_layer.objects.active = rig
    bpy.ops.object.mode_set(mode="EDIT")

    def bone(name, start, end, parent=None):
        item = armature.edit_bones.new(name)
        item.head = Vector(start) * height
        item.tail = Vector(end) * height
        if parent:
            item.parent = armature.edit_bones[parent]
            item.use_connect = (item.head - item.parent.tail).length < 0.0001
        return item

    bone("root", (0, 0, 0), (0, 0, 0.18)).use_deform = False
    bone("hips", (0, 0, 0.51), (0, 0, 0.60), "root")
    bone("spine", (0, 0, 0.60), (0, 0, 0.72), "hips")
    bone("chest", (0, 0, 0.72), (0, 0, 0.83), "spine")
    bone("neck", (0, 0, 0.83), (0, 0, 0.89), "chest")
    bone("head", (0, 0, 0.89), (0, 0, 0.99), "neck")

    arm_positions = {}
    for sign, suffix, hand_height in zip((1, -1), ("L", "R"), hand_heights):
        hand = sign * float(np.percentile((x * sign)[x * sign > 0], 99.5))
        hand = (hand, -0.012, hand_height)
        shoulder = (sign * 0.093, 0.008, 0.81)
        wrist = tuple(a + (b - a) * 0.83 for a, b in zip(shoulder, hand))
        elbow = tuple(a + (b - a) * 0.48 for a, b in zip(shoulder, hand))
        arm_positions[suffix] = shoulder[0], elbow[0], wrist[0], hand_height
        bone(f"collar.{suffix}", (0, 0, 0.81), shoulder, "chest")
        bone(f"upper_arm.{suffix}", shoulder, elbow, f"collar.{suffix}")
        bone(f"forearm.{suffix}", elbow, wrist, f"upper_arm.{suffix}")
        bone(f"hand.{suffix}", wrist, hand, f"forearm.{suffix}")
        hip = (sign * 0.071, 0, 0.52)
        knee = (sign * 0.071, -0.005, 0.29)
        ankle = (sign * 0.083, 0.012, 0.065)
        bone(f"thigh.{suffix}", hip, knee, "hips")
        bone(f"shin.{suffix}", knee, ankle, f"thigh.{suffix}")
        bone(f"foot.{suffix}", ankle, (sign * 0.085, -0.068, 0.025), f"shin.{suffix}")
    bpy.ops.object.mode_set(mode="OBJECT")

    weights = {}
    arm_parts = np.zeros_like(z)
    for sign, suffix in ((1, "L"), (-1, "R")):
        shoulder, elbow, wrist, hand_height = arm_positions[suffix]
        lateral = x * sign
        # Treat the hanging sleeve as part of its arm while keeping the robe on the torso.
        outer_sleeve = smoothstep(0.126, 0.195, lateral)
        sleeve_floor = hand_height - 0.20 + (0.25 - lateral) * 0.38
        arm = outer_sleeve * smoothstep(sleeve_floor, sleeve_floor + 0.10, z)
        upper = smoothstep(abs(shoulder) * 0.75, abs(shoulder) + 0.045, lateral)
        forearm = smoothstep(abs(elbow) - 0.04, abs(elbow) + 0.04, lateral)
        hand = smoothstep(abs(wrist) - 0.025, abs(wrist) + 0.018, lateral)
        hand *= smoothstep(hand_height - 0.10, hand_height - 0.045, z)
        weights[f"collar.{suffix}"] = arm * (1 - upper)
        weights[f"upper_arm.{suffix}"] = arm * upper * (1 - forearm)
        weights[f"forearm.{suffix}"] = arm * upper * forearm * (1 - hand)
        weights[f"hand.{suffix}"] = arm * upper * forearm * hand
        arm_parts += arm

    body = 1 - arm_parts
    leg = 1 - smoothstep(0.43, 0.57, z)
    shin = 1 - smoothstep(0.25, 0.34, z)
    foot = 1 - smoothstep(0.06, 0.10, z)
    spine = smoothstep(0.59, 0.69, z)
    chest = smoothstep(0.69, 0.78, z)
    neck = smoothstep(0.81, 0.865, z)
    head = smoothstep(0.845, 0.895, z)
    torso = body * (1 - leg)
    weights["hips"] = torso * (1 - spine)
    weights["spine"] = torso * spine * (1 - chest)
    weights["chest"] = torso * spine * chest * (1 - neck)
    weights["neck"] = torso * spine * chest * neck * (1 - head)
    weights["head"] = torso * spine * chest * neck * head
    left_leg = smoothstep(-0.035, 0.035, x)
    for suffix, share in (("L", left_leg), ("R", 1 - left_leg)):
        lower = body * leg * share
        weights[f"thigh.{suffix}"] = lower * (1 - shin)
        weights[f"shin.{suffix}"] = lower * shin * (1 - foot)
        weights[f"foot.{suffix}"] = lower * shin * foot

    values = np.stack(list(weights.values()), axis=1)
    values[values < 0.002] = 0
    totals = values.sum(axis=1)
    assert np.all(totals > 0), "Found vertices without a deform bone"
    values /= totals[:, None]
    for column, name in enumerate(weights):
        group = mesh.vertex_groups.new(name=name)
        nonzero_indices = np.flatnonzero(values[:, column])
        quantized = (values[nonzero_indices, column] * 1000).round().astype(int)
        order = np.argsort(quantized)
        levels, starts = np.unique(quantized[order], return_index=True)
        groups = np.split(nonzero_indices[order], starts[1:])
        for weight, indices in zip(levels, groups):
            group.add(indices.tolist(), float(weight) / 1000, "REPLACE")
    modifier = mesh.modifiers.new("Character skin", "ARMATURE")
    modifier.object = rig
    modifier.use_deform_preserve_volume = True
    mesh.parent = rig
    return rig


def add_pose_preview(rig):
    scene = bpy.context.scene
    scene.render.fps = 30
    scene.frame_start, scene.frame_end = 1, 60
    for bone in rig.pose.bones:
        bone.rotation_mode = "QUATERNION"
        bone.keyframe_insert(data_path="rotation_quaternion", frame=1)
        bone.keyframe_insert(data_path="rotation_quaternion", frame=60)

    def aim(name, direction):
        bone = rig.pose.bones[name]
        desired = bone.matrix.to_3x3().inverted() @ Vector(direction).normalized()
        bone.rotation_quaternion = Vector((0, 1, 0)).rotation_difference(desired)
        bpy.context.view_layer.update()

    for sign, suffix in ((1, "L"), (-1, "R")):
        aim(f"upper_arm.{suffix}", (sign * 0.22, -0.56, -0.80))
        aim(f"forearm.{suffix}", (-sign * 0.20, -0.94, 0.12))
        aim(f"hand.{suffix}", (-sign * 0.11, -0.93, 0.02))
        aim(f"thigh.{suffix}", (sign * 0.20, -0.74, -0.64))
        aim(f"shin.{suffix}", (sign * 0.03, 0.46, -0.89))
        aim(f"foot.{suffix}", (0, -0.96, -0.28))
    for bone in rig.pose.bones:
        bone.keyframe_insert(data_path="rotation_quaternion", frame=30)
    rig.animation_data.action.name = "Broom Pose Check"
    scene.timeline_markers.new("Neutral", frame=1)
    scene.timeline_markers.new("Broom pose check", frame=30)
    scene.frame_set(1)


def main():
    OUTPUT.mkdir(exist_ok=True)
    for character, hands in CHARACTERS.items():
        (source,) = SOURCE.glob(f"{character}_*.glb")
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.gltf(filepath=str(source))
        (mesh,) = [obj for obj in bpy.context.scene.objects if obj.type == "MESH"]
        mesh.name = character.replace("_", " ")
        rig = build_rig(mesh, hands)
        add_pose_preview(rig)
        bpy.ops.file.pack_all()
        bpy.context.preferences.filepaths.save_version = 0
        bpy.ops.wm.save_as_mainfile(filepath=str(OUTPUT / f"{character}_Rigged.blend"), compress=True)
        print(f"{character}: {len(rig.data.bones)} bones, {len(mesh.data.vertices)} skinned vertices")


if __name__ == "__main__":
    main()
