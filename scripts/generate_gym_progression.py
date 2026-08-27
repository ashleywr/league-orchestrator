#!/usr/bin/env python3
"""Generate an RCT requiredDefeats progression DAG for a pool of gym trainers.

League Orchestrator does not own trainer progression or level pacing -- RCT
does, via each trainer's requiredDefeats graph (see CONTENT_PACK_FORMAT.md,
"Trainer progression and level pacing belong to RCT, not League"). This tool
only edits that field. It never touches team rosters, dialog, or any other
trainer data, and it never talks to a running server -- its output is a
datapack you commit/ship/reload like any other.

Input: a directory of existing RCT trainer progression files
(data/rctmod/mobs/trainers/single/<id>.json). Every file's requiredDefeats is
replaced; every other field is preserved untouched.

Usage:
    python generate_gym_progression.py --input-dir <path> --output-dir <path> \
        [--count N] [--seed SEED] [--lookback 2]

If --count is omitted, every trainer in the input pool is used. If --count is
less than the pool size, a seeded subset is chosen and the rest are left out
of the generated output entirely (the campaign author should not reference an
excluded trainer's id from a gym archetype).
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path


def load_pool(input_dir: Path) -> dict[str, dict]:
    pool = {}
    for path in sorted(input_dir.glob("*.json")):
        with path.open(encoding="utf-8") as handle:
            pool[path.stem] = json.load(handle)
    return pool


def tier_sizes(n: int) -> list[int]:
    """ceil(sqrt(n)) tiers, front-loaded so the open (tier-1) set is largest."""
    tier_count = max(1, math.ceil(math.sqrt(n)))
    base, remainder = divmod(n, tier_count)
    return [base + (1 if i < remainder else 0) for i in range(tier_count)]


def build_tiers(trainer_ids: list[str], rng: random.Random) -> list[list[str]]:
    shuffled = list(trainer_ids)
    rng.shuffle(shuffled)
    sizes = tier_sizes(len(shuffled))
    tiers, index = [], 0
    for size in sizes:
        tiers.append(shuffled[index:index + size])
        index += size
    return tiers


def required_defeats_for_tier(tiers: list[list[str]], tier_index: int, lookback: int) -> list[list[str]]:
    if tier_index == 0:
        return []
    start = max(0, tier_index - lookback)
    return [sorted(tiers[i]) for i in range(start, tier_index)]


def generate(input_dir: Path, output_dir: Path, count: int | None, seed: int | None, lookback: int) -> dict:
    pool = load_pool(input_dir)
    if not pool:
        raise SystemExit(f"No trainer progression files found in {input_dir}")

    all_ids = sorted(pool.keys())
    n = count if count is not None else len(all_ids)
    if n < 1 or n > len(all_ids):
        raise SystemExit(f"--count must be between 1 and {len(all_ids)} (pool size), got {n}")

    rng = random.Random(seed)
    selected = all_ids if n == len(all_ids) else sorted(rng.sample(all_ids, n))
    excluded = sorted(set(all_ids) - set(selected))

    tiers = build_tiers(selected, rng)

    output_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for tier_index, tier in enumerate(tiers):
        required = required_defeats_for_tier(tiers, tier_index, lookback)
        for trainer_id in tier:
            data = dict(pool[trainer_id])  # preserve every other authored field
            data["requiredDefeats"] = required
            out_path = output_dir / f"{trainer_id}.json"
            with out_path.open("w", encoding="utf-8") as handle:
                json.dump(data, handle, indent=2)
                handle.write("\n")
            written.append((trainer_id, tier_index, required))

    return {
        "pool_size": len(all_ids),
        "selected": selected,
        "excluded": excluded,
        "tiers": tiers,
        "written": written,
    }


def validate(output_dir: Path, selected: list[str]) -> list[str]:
    """Re-parses the generated files and checks referential integrity + acyclicity."""
    errors = []
    known = set(selected)
    edges = {}
    for trainer_id in selected:
        with (output_dir / f"{trainer_id}.json").open(encoding="utf-8") as handle:
            data = json.load(handle)
        refs = set()
        for group in data.get("requiredDefeats", []):
            for ref in group:
                refs.add(ref)
                if ref not in known:
                    errors.append(f"{trainer_id} requires unknown trainer '{ref}'")
        edges[trainer_id] = refs

    visited, stack = set(), set()

    def visit(node):
        if node in stack:
            errors.append(f"cycle detected at {node}")
            return
        if node in visited:
            return
        stack.add(node)
        for dep in edges.get(node, ()):
            visit(dep)
        stack.discard(node)
        visited.add(node)

    for node in edges:
        visit(node)
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--count", type=int, default=None, help="How many trainers to use (default: whole pool).")
    parser.add_argument("--seed", type=int, default=None, help="Selection/tier-shuffle seed (default: nondeterministic).")
    parser.add_argument("--lookback", type=int, default=2, help="How many prior tiers each trainer's requiredDefeats reaches back into.")
    args = parser.parse_args()

    result = generate(args.input_dir, args.output_dir, args.count, args.seed, args.lookback)
    errors = validate(args.output_dir, result["selected"])

    print(f"Pool size: {result['pool_size']}; selected: {len(result['selected'])}; excluded: {result['excluded'] or 'none'}")
    for tier_index, tier in enumerate(result["tiers"]):
        print(f"  tier {tier_index}: {tier}")
    for trainer_id, tier_index, required in result["written"]:
        print(f"  {trainer_id} (tier {tier_index}) requiredDefeats={required}")

    if errors:
        print("VALIDATION FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        return 1

    print("Validation passed: every reference resolves, no cycles.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
