# League Orchestrator

League Orchestrator is a standalone Fabric 1.21.1 Cobblemon campaign layer. It
requires Region Engine for deterministic continent/profile context and Cobblemon
for wild-Pokémon spawn enforcement. It intentionally does **not** include a
trainer battle engine, structure library, badge artwork, or Koyo branding.

## Current vertical slice

- Loads datapack-defined regional species pools and gym archetypes.
- Cancels normal wild Cobblemon spawns that are not allowed by the active
  Region Engine profile's `cobblemon.allow_species_tags` / `deny_species_tags`.
- Treats an unresolved allow pool as a safe pass-through. A modpack can ship
  Region Engine profiles before it ships the matching Pokémon content data.
- Creates one saved, deterministic allocation ledger per world. Calling the
  allocation service for a `(atlas site, slot, pool)` returns the same gym on
  every later call, and a selected gym is removed from that pool globally.
- Provides `/league info`, `/league site`, and the operator test command
  `/league claim <pool>`.

The next vertical slice is a physical site/structure registration bridge:
worldgen or a builder anchor will claim an allocation, record its position, and
become a navigation target. RCT will be the first trainer bridge, using its
public API rather than copied implementation.

## Content data

Content belongs in a normal server datapack. `species_pools` are referenced by
the exact resource ID already placed in Region Engine profiles.

`data/region_engine/region_engine/region_profiles/kanto.json`:

```json
{
  "cobblemon": {
    "allow_species_tags": ["league:kanto_native"],
    "deny_species_tags": ["league:johto_native"]
  }
}
```

`data/league/league_orchestrator/species_pools/kanto_native.json`:

```json
{
  "species": [
    "cobblemon:bulbasaur",
    "cobblemon:charmander",
    "cobblemon:squirtle"
  ]
}
```

An allow list applies only after its corresponding pool has loaded. Add all
forms/species wanted by the pack; the filter compares Cobblemon species IDs and
does not infer a real-world region from a biome.

Gym archetypes are intentionally separate from regional Pokémon pools:

`data/league/league_orchestrator/gym_archetypes/verdant.json`:

```json
{
  "pool": "starter_badges",
  "region_profiles": ["region_engine:kanto", "region_engine:johto"],
  "weight": 2,
  "structure_tag": "league:verdant_gym",
  "trainer": "rctmod:leader_example",
  "badge": "league:verdant"
}
```

If `starter_badges` has five entries and five Atlas sites claim that pool, each
entry is allocated once. Use a single pool to emulate unique local leaders, or
separate pools such as `fire_badges`, `water_badges`, and `grass_badges` to
randomize independently by Pokémon type. The resolved allocation is stored in
world saved data and is never normally reshuffled.

## Development

This inner repository is intentionally separate from Region Engine and Koyo.
The local build uses development-only compile contracts for the adjacent Region
Engine build and the local Cobblemon 1.7.3 jar; neither is embedded in the
output.

```powershell
.\gradlew.bat clean build --no-daemon
```
