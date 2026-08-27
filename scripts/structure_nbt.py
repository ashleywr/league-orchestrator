#!/usr/bin/env python3
"""Inspect and perform conservative edits on League structure NBT files.

The utility keeps source NBT files untouched unless an explicit output path is
supplied. It handles the gzip-compressed structure format produced by Minecraft
structure blocks and can configure League-owned jigsaw sockets.
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

import nbtlib


def load_structure(path: Path) -> nbtlib.File:
    return nbtlib.load(path, gzipped=True)


def block_name(entry: nbtlib.Compound) -> str:
    return str(entry["Name"])


def palette_entry(root: nbtlib.File, block: nbtlib.Compound) -> nbtlib.Compound:
    return root["palette"][int(block["state"])]


def pos(block: nbtlib.Compound) -> tuple[int, int, int]:
    return tuple(int(value) for value in block["pos"])


def print_summary(root: nbtlib.File) -> None:
    size = tuple(int(value) for value in root["size"])
    blocks = root["blocks"]
    counts = Counter(block_name(palette_entry(root, block)) for block in blocks)
    block_entities = Counter(
        str(block["nbt"]["id"])
        for block in blocks
        if "nbt" in block and "id" in block["nbt"]
    )

    print(f"size: {size[0]} x {size[1]} x {size[2]}")
    print(f"data_version: {root.get('DataVersion', '<missing>')}")
    print(f"cobblemon_data_version: {root.get('cobblemon:data_version', '<missing>')}")
    print(f"blocks: {len(blocks)}")
    print(f"entities: {len(root.get('entities', []))}")
    print(f"block_entities: {sum(1 for block in blocks if 'nbt' in block)}")
    print(f"palette_entries: {len(root['palette'])}")

    print("\nblock_counts:")
    for name, count in counts.most_common():
        print(f"  {count:5} {name}")

    print("\nblock_entity_ids:")
    for name, count in block_entities.most_common():
        print(f"  {count:5} {name}")

    print("\njigsaws:")
    found_jigsaw = False
    for block in blocks:
        if block_name(palette_entry(root, block)) != "minecraft:jigsaw":
            continue
        found_jigsaw = True
        nbt = block.get("nbt", {})
        print(
            "  "
            f"pos={pos(block)} "
            f"name={nbt.get('name', '<missing>')} "
            f"target={nbt.get('target', '<missing>')} "
            f"pool={nbt.get('pool', '<missing>')} "
            f"final_state={nbt.get('final_state', '<missing>')}"
        )
    if not found_jigsaw:
        print("  none")


def validate(root: nbtlib.File) -> list[str]:
    errors: list[str] = []
    warnings: list[str] = []
    size = tuple(int(value) for value in root["size"])
    palette_size = len(root["palette"])

    if len(size) != 3 or any(value <= 0 for value in size):
        errors.append(f"invalid size: {size}")

    for index, block in enumerate(root["blocks"]):
        state = int(block["state"])
        if state < 0 or state >= palette_size:
            errors.append(f"block {index} has out-of-range state {state}")
            continue
        coordinates = pos(block)
        if any(coordinate < 0 or coordinate >= size[axis] for axis, coordinate in enumerate(coordinates)):
            errors.append(f"block {index} position {coordinates} is outside size {size}")
        if "nbt" in block and "id" not in block["nbt"]:
            warnings.append(f"block {index} at {coordinates} has block NBT without an id")

        if block_name(root["palette"][state]) == "minecraft:jigsaw":
            nbt = block.get("nbt", {})
            final_state = str(nbt.get("final_state", ""))
            if final_state == "apricot plankss":
                warnings.append(f"jigsaw at {coordinates} has invalid final_state {final_state!r}")

    for message in errors:
        print(f"ERROR: {message}", file=sys.stderr)
    for message in warnings:
        print(f"WARNING: {message}", file=sys.stderr)
    return errors


def remove_jigsaws(root: nbtlib.File) -> int:
    old_blocks = root["blocks"]
    kept = [
        block
        for block in old_blocks
        if block_name(palette_entry(root, block)) != "minecraft:jigsaw"
    ]
    removed = len(old_blocks) - len(kept)
    old_palette = root["palette"]
    state_map: dict[int, int] = {}
    compact_palette = nbtlib.List[nbtlib.Compound]()
    for block in kept:
        old_state = int(block["state"])
        if old_state not in state_map:
            state_map[old_state] = len(compact_palette)
            compact_palette.append(old_palette[old_state])
        block["state"] = nbtlib.Int(state_map[old_state])
    root["blocks"] = nbtlib.List[nbtlib.Compound](kept)
    root["palette"] = compact_palette
    return removed


def clear_container_items(root: nbtlib.File) -> int:
    changed = 0
    for block in root["blocks"]:
        nbt = block.get("nbt")
        if nbt is None or "Items" not in nbt:
            continue
        if len(nbt["Items"]) == 0:
            continue
        nbt["Items"] = nbtlib.List[nbtlib.Compound]()
        changed += 1
    return changed


def state_for_block(root: nbtlib.File, name: str) -> int:
    """Return a palette state for a simple block, adding it when necessary."""
    for index, entry in enumerate(root["palette"]):
        if block_name(entry) == name and "Properties" not in entry:
            return index
    root["palette"].append(nbtlib.Compound({"Name": nbtlib.String(name)}))
    return len(root["palette"]) - 1


def configure_veridian_city_connectors(root: nbtlib.File) -> int:
    """Configure League-owned sockets and remove the third-party NPC hook.

    The entrance is a child connector for a future Veridian city center. The
    copied bottom socket points at an empty League-owned decor pool so it can
    later gain city decoration without referring to a vanilla test pool. The
    former NPC jigsaw becomes regular apricorn planks, leaving no external
    entity or template-pool reference in this structure.
    """
    blocks_by_position = {
        pos(block): block
        for block in root["blocks"]
        if block_name(palette_entry(root, block)) == "minecraft:jigsaw"
    }
    expected_positions = {(0, 0, 16), (0, 1, 11), (9, 1, 7)}
    missing = expected_positions - blocks_by_position.keys()
    if missing:
        missing_text = ", ".join(str(value) for value in sorted(missing))
        raise ValueError(f"expected Pokécenter jigsaw positions are missing: {missing_text}")

    entrance = blocks_by_position[(0, 1, 11)]["nbt"]
    entrance.update(
        {
            "name": nbtlib.String("league_orchestrator:veridian_pokecenter"),
            "target": nbtlib.String("league_orchestrator:veridian_pokecenter_connector"),
            "pool": nbtlib.String("minecraft:empty"),
            "final_state": nbtlib.String("minecraft:stone"),
            "joint": nbtlib.String("rollable"),
        }
    )

    bottom = blocks_by_position[(0, 0, 16)]["nbt"]
    bottom.update(
        {
            "name": nbtlib.String("league_orchestrator:veridian_bottom"),
            "target": nbtlib.String("league_orchestrator:veridian_bottom"),
            "pool": nbtlib.String("league_orchestrator:veridian/decor"),
            "final_state": nbtlib.String("minecraft:dirt"),
            "joint": nbtlib.String("rollable"),
        }
    )

    nurse = blocks_by_position[(9, 1, 7)]
    nurse["state"] = nbtlib.Int(state_for_block(root, "cobblemon:apricorn_planks"))
    del nurse["nbt"]
    return 3


def save_structure(root: nbtlib.File, source: Path, output: Path) -> None:
    source_resolved = source.resolve()
    output_resolved = output.resolve()
    if source_resolved == output_resolved:
        raise ValueError("refusing to overwrite the input; choose a separate output path")
    output.parent.mkdir(parents=True, exist_ok=True)
    root.save(output, gzipped=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    inspect_parser = subparsers.add_parser("inspect", help="print a structure summary")
    inspect_parser.add_argument("input", type=Path)

    validate_parser = subparsers.add_parser("validate", help="validate positions and palette references")
    validate_parser.add_argument("input", type=Path)

    edit_parser = subparsers.add_parser("edit", help="write a conservative edited copy")
    edit_parser.add_argument("input", type=Path)
    edit_parser.add_argument("output", type=Path)
    edit_parser.add_argument(
        "--remove-jigsaws",
        action="store_true",
        help="remove minecraft:jigsaw blocks and their block entities",
    )
    edit_parser.add_argument(
        "--clear-container-items",
        action="store_true",
        help="empty Items lists in containers such as chests and barrels",
    )
    edit_parser.add_argument(
        "--configure-veridian-city-connectors",
        action="store_true",
        help="configure the Pokécenter's League-owned Veridian city jigsaws",
    )

    args = parser.parse_args()
    root = load_structure(args.input)

    if args.command == "inspect":
        print_summary(root)
        return 0

    if args.command == "validate":
        return 1 if validate(root) else 0

    changed: list[str] = []
    if args.remove_jigsaws:
        changed.append(f"removed {remove_jigsaws(root)} jigsaw blocks")
    if args.clear_container_items:
        changed.append(f"cleared {clear_container_items(root)} non-empty containers")
    if args.configure_veridian_city_connectors:
        changed.append(
            f"configured {configure_veridian_city_connectors(root)} Veridian Pokecenter sockets"
        )
    if not changed:
        parser.error("edit requires at least one edit option")
    if validate(root):
        return 1
    save_structure(root, args.input, args.output)
    print(f"wrote: {args.output}")
    for message in changed:
        print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
