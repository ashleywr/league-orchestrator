# League region content-pack template

Copy this directory into a standalone datapack, then replace every occurrence
of `example_region` with your own lower-case namespace. It is intentionally
inert as shipped: it defines a profile and campaign but does **not** override
Region Engine's site assignment.

This is content, not Java. A modpack author can define a named location,
its terrain-biome vocabulary, Cobblemon compatibility, and future spawn data
without recompiling League Orchestrator, Region Engine, Terra, or Cobblemon.

When used with Terra, this datapack supplies the named campaign and semantic
biome vocabulary; the companion Terra pack still selects the real vanilla or
modded biome IDs. Do not create a synthetic replacement biome just to carry
the location name. F3 and all ordinary biome consumers should keep seeing the
real selected biome.

## Required edits

1. Choose a namespace, such as `amber_coast`.
2. Rename the `data/example_region` directory to `data/amber_coast`.
3. Replace `example_region` in every JSON file and update the display names.
4. Put the actual generated biome IDs in `data/amber_coast/tags/worldgen/biome/`.
   The supplied values use optional placeholder IDs so the untouched template
   remains loadable but has no biome membership.
5. Set `biome_pool` in `region_profiles/amber_coast.json` to the broad tag
   that represents the allowed land biomes for that region.
6. Copy `overrides/region_engine/site_assignment.json.example` into your
   datapack as `data/region_engine/site_assignment.json` only when you are
   ready to assign an Atlas site to the new profile. Merge rather than blindly
   replacing existing assignments.

For a Terra-only pack, omit step 6 and use this template for the data-driven
taxonomy, Cobblemon bridge, and campaign content. The shipped Terra bridge is
currently Kanto-specific; a wholly new Terra preset needs a matching League
geographic-profile bridge rather than an inferred identity from terrain. Keep
the Terra biome-selection rules limited to IDs listed by this datapack's broad
`biomes` tag once that bridge exists.

The campaign data path deliberately contains `league_orchestrator` as a path
component: League's loader reads `league_orchestrator/campaigns` from every
data namespace. Therefore this file becomes the campaign
`amber_coast:amber_coast` after the rename.

## Biome taxonomy

Own narrow, geographic tags under your namespace:

- `#amber_coast:biomes` — all surface biomes permitted by the profile.
- `#amber_coast:forest`, `plains`, `desert`, `marsh`, and `mountains` —
  surface habitats.
- `#amber_coast:ice_cap` — a high-altitude biome, if the terrain has one.
- `#amber_coast:ocean` and `caves` — distinct aquatic and underground
  identities.

The files under `data/cobblemon/tags/worldgen/biome/` are additive bridges to
Cobblemon's established semantic tags. They make existing Cobblemon entries
that test `#cobblemon:is_forest`, `is_temperate`, `is_mountain`, and so on
recognize the custom geography automatically. Leave `"replace": false`.

For location-specific future spawns, add normal Cobblemon files beneath
`data/amber_coast/spawn_pool_world/` and refer to the owned tags directly,
for example `"biomes": ["#amber_coast:forest"]`. Do not copy the whole
Cobblemon spawn pool.

## Rivers, lakes, and caves

Biome tags classify biome identity; they do not identify every block feature.
If a generator creates rivers and lakes by carving water through otherwise
contiguous surface biomes, do not add all route-country biomes to
`#cobblemon:is_river`. Use Cobblemon's `submerged`, `surface`, or `fishing`
position types and `neededNearbyBlocks: ["minecraft:water"]`, combined with
an owned land-habitat tag.

Likewise, preserve real cave-biome IDs in the `caves` tag. A mountain cavern
should be a cave biome only below its surface, never a new speckled surface
biome. See [CAVE_LANDMARKS.md](CAVE_LANDMARKS.md) before adding geological
landmarks.

## Reload and testing

Install the datapack before creating a fresh world that will use the profile.
Use `/reload` to validate resource syntax and inspect the server log for
campaign/profile validation messages. Initial site assignment, worldgen, town
planning, and RoadWeaver's first scan are saved per world, so use a fresh test
world after changing them.
