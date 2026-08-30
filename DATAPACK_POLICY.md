# League Orchestrator content-pack policy

League Orchestrator's campaign content is deliberately a per-region datapack
layer: each campaign declares the stable profile IDs it owns, and packs can be
added without editing a global campaign list. Region Engine supplies that
identity when its Atlas integration is active; Terra Kanto supplies the same
League-facing geographic identity through the Terra-compatible bridge. The
bundled **Kanto Classic campaign content** pack owns Kanto; the built-in
procedural Frontier campaign safely covers every unclaimed profile.

## What a campaign pack owns

- League gym archetypes and regional species pools.
- League-owned biome and settlement tags.
- Original or redistribution-permitted campaign templates, jigsaw pools, and
  registered structures. A pack must retain an `ATTRIBUTION.md` with asset
  author, source URL, permission/license, and required credit. Permission to
  use a map in a modpack must cover distributing its converted NBT/template
  assets.
- Its intended geographic-profile assignments, such as
  `region_engine:kanto` where Region Engine is installed. Terra Kanto retains
  that campaign identity without requiring Region Engine to be present.
- One original RCT series resource under `data/rctmod/series/`, identified by
  the campaign's required `rct_series` field. Frontier is intentionally the
  sole no-series fallback; RCT itself remains a hard core dependency.
- Empty RGS gym biome tags. This is a safety suppression, not a content
  replacement: it prevents Radical Gyms & Structures from independently
  generating competing gyms while League Orchestrator owns gym allocation.

## What it may observe, but never replace

The bundled Kanto BCA pilot replaces League's settlement tag with optional BCA
village structure IDs and replaces the runtime `minecraft:villages` placement
with League's own pure placement codec. The replacement names BCA's public
registered structure IDs but does not copy its templates, pools, loot, or
other assets. Minecraft remains responsible for generating the selected BCA
jigsaw start; League registers a RoadWeaver town only after that succeeds. The
test profile suppresses competing CTOV, MMV, and Qrafty towns. When BCA is
absent, the optional references disappear cleanly.

The same rule applies to every third-party structure provider: an integration
may identify a public structure ID or attach a runtime fallback endpoint, but
it must not ship copied assets or replace the provider's worldgen/content data.

League may place assets that the campaign pack itself lawfully supplies. This
does not authorize copying BCA/RGS/another mod's template data merely because
League can observe that mod's generated structure.

## Graceful fallback rules

- Missing optional structures, tags, species pools, trainers, or template
  providers must leave the relevant feature inactive rather than produce a
  replacement resource.
- The core mod remains useful with Cobblemon and its required trainer
  integrations; Region Engine is optional and Terra Kanto is the active
  finite-world route.
- Biome-mod compatibility is additive and pack-owned. League ships no
  per-biome-mod override packs; a pack author can extend
  `league_orchestrator:kanto_biomes` with their own optional datapack.
- A Kanto world always has the vanilla-biome baseline in that tag, so an absent
  biome mod never invalidates the profile.

## Campaign selection and order

The bundled Kanto Classic pack is globally enabled whenever its required BCA
provider is installed. It supplies the Kanto profile and League definitions
without needing a per-world selection step. Additional campaign packs must be
non-overlapping; any profile with no specialist pack resolves to Frontier.

Neither Region Engine's Atlas nor Terra's generator configuration is a
campaign datapack. An external campaign pack may add League definitions or
additive biome-tag entries, but must not replace generated worldgen after a
world has begun. Terra Kanto uses real vanilla/Regions Unexplored biome IDs;
campaign tags classify those IDs and never replace their biome effects.

The first valid campaign resolved for a profile is saved with the world plan.
This lets a newly-added pack govern a previously untouched profile, while never
rerolling a profile players have already entered. A pack may declare exact
required mod IDs for its trainer, town, decor, reward, or TM features; a missing
required provider falls through to another campaign or Frontier instead of
loading a partial imitation. The data shapes are documented in
[CONTENT_PACK_FORMAT.md](CONTENT_PACK_FORMAT.md).

Legacy BCA Kanto Towns and Content Compatibility packs were removed because
they replaced third-party worldgen and loot data instead of falling back safely.

## Future licensed map/module packs

A permissively shared regional map may become a campaign's asset source, but
not a single unstructured random pool. The intended format is a set of
licensed macro modules with explicit compatible edge sockets (road, shore,
river, cave, elevation) and smaller building/path templates within each module.
League will save a seeded finite graph before placing blocks. This renderer is
not live yet; content should not depend on raw NBT or jigsaw connectors being
automatically recognized as League road ports. See
[ARCHITECTURE.md](ARCHITECTURE.md).

## Villages: never restate vanilla

A campaign pack that overrides `minecraft:villages` is competing with whatever
village mod the pack already ships. Two techniques are in play and they need
opposite handling:

- **Runtime pool injection** - CTOV and Better Village ship *no* structure
  files at all. They replace the buildings inside vanilla village jigsaw pools
  while the game runs, so `minecraft:village_plains` still generates but is no
  longer a vanilla village. Listing the vanilla structures is correct with
  these installed and wrong without them.
- **Structure set replacement** - BCA overrides `minecraft:villages` with
  `bca:village/*`. Whichever pack loads last wins, so a League override that
  restates the vanilla list can silently reinstate plain villages.

League overrides `minecraft:villages` to apply the
`league_orchestrator:outside_league` placement, which switches villages off
inside the mounted League dimension so the campaign's own towns are the only
settlements there. It normally restates the active provider's structure list
rather than substituting vanilla structures. A regional campaign may make a
small, explicit climate correction to that list when a provider template is
geographically impossible for the profile; it must also constrain that
template's structure biome tag. This remains a selection rule, not a copy of
provider pools, templates, loot, or assets.

Kanto carries BCA's non-ice village families with BCA's spread values, excludes
the ice family from its warm-region village set, and limits the retained ice
structure definitions to Kanto's ice-cap tag as a defense in depth.

Do not reach for a structure-toggle mod to suppress vanilla villages. With an
improving mod installed there is no unaltered vanilla village to suppress, and
without one the structure list is the honest place to say so.
