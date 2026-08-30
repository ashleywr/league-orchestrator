# Terra Kanto runtime and validation guide

This is the current, supported Kanto world-generation route. It replaces the
earlier Region Engine/OTG experiments for the shared **Mod Test** Prism
instance. Region Engine remains an optional integration; OTG is not used by
this route.

## What was chosen and why

Terra is the terrain generator because it can compose a finite Kanto-like
continent and its density functions while retaining real registered biomes.
The Kanto pack therefore selects vanilla and Regions Unexplored biomes instead
of substituting a generic `terra:kanto` biome. This keeps F3, mod features,
biome effects, Cobblemon, and datapacks interoperable.

The generated geography has these deliberate constraints:

- ocean and a finite continent boundary rather than an endless landmass;
- contiguous climate zones, with tiny macro fragments merged before a biome is
  chosen;
- no ordinary desert and no ordinary ice biome; the only intended snow/ice
  context is the high landmark mountain cap;
- a seed-varying inland mountain, mountain-fed river valleys, a possible large
  inland lake, and protected cave/ore generation;
- actual forest, plain, marsh, highland, wisteria, orchard, maple, and related
  vanilla/Regions Unexplored identities chosen from the allowed Kanto pool;
- terrain-aware League sites, including viable coastal placements, rather than
  a fixed grid of towns; and
- BCA settlement archetypes selected according to their campaign tier and
  climate suitability.

The Kanto Terra hydrology add-on owns landmark and river-density shaping.
Lithostitched-compatible cave content is constrained to covered mountain
contexts; it must not create a surface cavern biome or remove normal ores.

## Required runtime pieces

Install these into the single shared instance:

`C:\Users\ashle\AppData\Roaming\PrismLauncher\instances\Mod Test\minecraft`

| Component | Destination | Responsibility |
|---|---|---|
| Terra Fabric | `mods` | The selected world generator. |
| Kanto Terra pack | `config\Terra\packs` | Biome selection, continent profile, and terrain expressions. |
| Kanto Terra Hydrology | `config\Terra\addons` | Landmark mountain, river valleys, lake opportunity, and terrain validation fields. |
| League Orchestrator | `mods\league_orchestrator-0.1.0.jar` | Campaign, sites/towns, semantic tags, and the RoadWeaver bridge. |
| RoadWeaver | `mods` | Initial regional scan, map tiles, routes, and roads. |
| Regions Unexplored and its dependencies | `mods` | The real modded Kanto biome entries and their in-game biome effects. |

The normal Kanto campaign and its semantic biome tags are bundled with League
Orchestrator. They are global: no AutoMine action, `/datapack enable`, or
per-world installation is required.

Only one test instance is maintained: **Mod Test**. Do not use a stale “OTG
Generator Test” instance as a second deployment target.

## Create and test a Kanto world

1. Stop Prism/Minecraft before replacing a runtime JAR, Terra pack, or Terra
   add-on.
2. On **Create New World**, choose **Terra Kanto** from World Type. Minecraft
   displays the selected preset as `generator:terra:kanto/kanto`; that is the
   preset ID, not a free-form seed string.
3. Choose a seed (or leave it blank) and create a **fresh** world. Do not use
   `League Dimension` to validate this terrain.
4. Wait for spawn preparation, League's buildable-site pass, and RoadWeaver's
   initial plan. A short playable spawn can appear before all route work has
   finished; the plan is ready when RoadWeaver reports its completed persisted
   connections.
5. Open RoadWeaver with `H`, zoom to the desired overview, explicitly request
   a resample if needed, wait for it to finish, then take the screenshot.

The first-world timing target is about **two to three minutes**. Record cold
chunk timing separately; it diagnoses runtime exploration performance and is
not interchangeable with initial planning time.

Changes to a generator, Terra pack/add-on, campaign site plan, structure
placement, or RoadWeaver initial scan require a new world. Existing worlds
retain generated starts and RoadWeaver persistence at
`data\roadweaver\persistence\structures\minecraft_overworld\state.json`.

## Biome and spawn contract

The selected overworld biome remains the actual registry entry. For example,
F3 may show `minecraft:forest` or a `regions_unexplored:*` biome. It should
not show a synthetic Kanto biome ID merely because that location belongs to
the Kanto campaign.

League expresses Kanto geography through additive tags:

- `#league_orchestrator:kanto_biomes` — allowed surface set;
- `#league_orchestrator:kanto_forest`, `kanto_plains`, `kanto_marsh`,
  `kanto_mountains`, `kanto_ocean`, and `kanto_ice_cap` — habitat semantics;
- `#league_orchestrator:kanto_caves` — real underground identities only; and
- additive `#cobblemon:is_*` tag contributions, so existing Cobblemon spawn
  rules recognize the same semantic habitat.

Future Pokémon data should target a semantic tag, for example
`#league_orchestrator:kanto_forest`, not a fragile list of individual biome
IDs. A pack author who wants an `amber_coast` or another named location can
create `#amber_coast:forest` and its Cobblemon/spawn data in a normal
datapack. The [region content-pack template](examples/league-region-content-pack-template/README.md)
contains the exact folder layout; Java recompilation is unnecessary for that
taxonomy. A wholly new Terra preset still needs a matching League geographic
profile bridge; the shipped Terra bridge intentionally recognizes the Kanto
profile only rather than guessing a profile from arbitrary terrain.

## Terrain, cave, and structure boundaries

- Terra and the hydrology add-on shape coast, heights, rivers, lakes, and the
  landmark mountain. Do not add a second continent generator in League.
- League validates a bounded set of candidate sites against the active Terra
  terrain. It may move to a wider search or skip an unusable slot; that is a
  protection against floating buildings and blocked chunk generation, not a
  seed-specific hard-code.
- BCA has final say over its terrain-sensitive jigsaw start. League only gives
  RoadWeaver realized, safe settlement endpoints; rejected starts cannot create
  phantom road destinations.
- RoadWeaver renders wilderness routes only. Town streets, doors, and internal
  path grammar are not RoadWeaver API concepts and stay League-owned/planned.
- Vanilla/Terra carving and ores remain enabled. A special mountain cavern has
  to be contained in the mountain's underground density field, below the cap
  and away from open air; it may decorate a real cave biome but may not become
  a speckled surface biome.

## Visual behavior and map behavior

Regions Unexplored provides its own water, grass, foliage, sky, and fog
effects. Because the Terra pack uses direct registered biomes, those effects
are available to the normal overworld renderer; tune the source biome choice
or its definitions rather than applying a global Kanto color override.

RoadWeaver's terrain palette now reads the selected biome’s water, grass, and
foliage effect colors. Its earlier overview palette did not. Map tiles cache
their rendered ARGB values, so restart and use a new world or an explicit map
resample after a RoadWeaver palette update. A map screenshot is not proof of
in-game foliage/water rendering; validate both directly when changing visuals.

## GPU and sampling

RoadWeaver uses its Terra-native sampler for a Terra generator. That sampler
is deliberately CPU-based: RoadWeaver's OpenCL density kernel only understands
Minecraft's vanilla `NoiseBasedChunkGenerator`, not Terra's arbitrary density
function graph. Treat the CPU log line as expected compatibility behavior, not
a fallback error. GPU acceleration would only be safe after RoadWeaver gains a
semantically equivalent Terra evaluator; forcing the vanilla kernel would make
roads disagree with terrain.

GPU availability can still help a supported vanilla generator. It is not the
correctness or timing mechanism for Terra Kanto.

## Reproducible acceptance run

For an initial generator, terrain, biome, hydrology, structure-placement, or
RoadWeaver-scan change, use the AutoMine benchmark runner against a uniquely
named fresh world in Mod Test. Preserve the result JSON, biome/terrain audit,
RoadWeaver state, and overview screenshot.

The runner must regard either of these as RoadWeaver completion:

1. the normal completion log event; or
2. persisted RoadWeaver state with a non-empty planned-route set and every
   recorded connection marked completed.

The second condition matters because an already persisted initial plan can be
valid even when the traditional transient log is not emitted. Launcher windows,
a world reaching 100%, and a single generated chunk are not completion proof.

The latest accepted fresh-world reference used seed `-4696667948374885479` and
reached the in-world state in 163 seconds, with 12 actual biome identities and
33 macro zones in its audit. It is a reference rather than a fixed seed:
different seeds must materially change the continent/biome layout while still
meeting the same biome, terrain, cave/ore, settlement, and timing gates.

## Safe deployment

Build League Orchestrator from `repo` with the local Cobblemon development JAR,
then deploy only `build\libs\league_orchestrator-0.1.0.jar`. Use the verified
deployment helper from the `minecraft-mod-deployment` workflow; it refuses to
replace the target while the Prism client is running and checks the source and
destination hashes. Never deploy a timestamp-selected wildcard because Gradle
also writes source JARs.

For every promotion:

1. keep the old JAR/pack as a recoverable backup outside the active `.jar` or
   active Terra pack path;
2. copy the specific runtime JAR, Terra pack, and add-on deliberately;
3. compare source and destination SHA-256 hashes;
4. start a fresh world for any initial-plan/worldgen change; and
5. preserve existing saves, unrelated mods, and benchmark evidence.

## Troubleshooting

| Observation | Expected action |
|---|---|
| `generator:terra:kanto/kanto` shown in world creation | It is the selected preset identifier. Use the Terra Kanto list entry; do not paste it as a seed. |
| RoadWeaver says CPU/does not use OpenCL for Terra | Expected. The native Terra sampler preserves route/terrain agreement. |
| Map lacks new biome colors after a palette update | Restart, create a fresh world or explicitly resample, then wait for the sample to finish. |
| F3 shows vanilla/RU biome rather than Kanto | Correct. Kanto is represented by semantic tags and campaign geography, not a replacement biome. |
| Town slots are skipped or widened | Generator-validated lowland search rejected unsafe sites. Inspect logs and improve constraints; do not force the original coordinate. |
| A new world has no finished routes yet | Inspect RoadWeaver persisted connection status, not only the spawn/loading bar. |
