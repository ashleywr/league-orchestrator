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

## Kanto Classic built-in pack

The mod includes the normal, opt-in **League Orchestrator — Kanto Classic
regional profile and species policy** data pack. Enable it above Region Engine
Atlas Test when creating a new world. It replaces only the `region_engine:kanto`
profile, leaving every other continent available for a different regional pack.

For the current built-in packs, data-pack priority from bottom to top is:

1. Any Region Engine biome-compatibility packs for installed biome mods.
2. **Region Engine — Atlas Test**.
3. **League Orchestrator — Kanto Classic regional profile and species policy**.

Kanto Classic must be highest because it overrides the base Kanto profile;
the compatibility packs only contribute optional biome-tag entries. Select the
packs before creating the world and do not alter the selected worldgen packs
after chunks have generated.

Kanto Classic uses a temperate Tokyo/Kanto-inspired palette: plains, forests,
river valleys, beaches, wetlands, and modest windswept/stony mountains. It
deliberately excludes jungle, desert, badlands, taiga, frozen, and ice biomes.
Its profile terrain favors mixed lowlands and mountains while minimizing large
inland lakes.

The initial species policy contains the 146 non-mythical Generation-I Cobblemon
species. Articuno, Zapdos, Moltres, and Mewtwo are denied to normal wild spawns
and reserved for future structure encounters; Mew is not included in the normal
Kanto encounter policy. This is a regional eligibility layer, not a copied
FireRed/LeafGreen or HeartGold/SoulSilver encounter table: it prevents unrelated
species from leaking in, but does not invent a spawn entry where Cobblemon has
none. The next content pass will supply original habitat/route-tier spawn pools
and the separately curated HeartGold/SoulSilver Kanto extension set.

## Development

This inner repository is intentionally separate from Region Engine and Koyo.
The local build uses development-only compile contracts for the adjacent Region
Engine build and a user-supplied Cobblemon 1.7.3 jar; neither is embedded in
the output.

```powershell
.\gradlew.bat clean build --no-daemon -Pcobblemon_dev_jar="C:\path\to\Cobblemon-fabric-1.7.3+1.21.1.jar"
```
