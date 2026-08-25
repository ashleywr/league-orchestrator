# League Orchestrator content-pack policy

Region Engine's Atlas is core, always-active worldgen. League Orchestrator's
campaign content is deliberately a per-region datapack layer: each campaign
declares which Region Engine profile IDs it owns, and packs can be added without
editing a global campaign list. The bundled **Kanto Classic campaign content**
pack owns Kanto; the built-in procedural Frontier campaign safely covers every
unclaimed profile.

## What a campaign pack owns

- League gym archetypes and regional species pools.
- League-owned biome and settlement tags.
- Original or redistribution-permitted campaign templates, jigsaw pools, and
  registered structures. A pack must retain an `ATTRIBUTION.md` with asset
  author, source URL, permission/license, and required credit. Permission to
  use a map in a modpack must cover distributing its converted NBT/template
  assets.
- Its intended Region Engine profile overrides, such as `region_engine:kanto`.
- One original RCT series resource under `data/rctmod/series/`, identified by
  the campaign's required `rct_series` field. Frontier is intentionally the
  sole no-series fallback; RCT itself remains a hard core dependency.
- Empty RGS gym biome tags. This is a safety suppression, not a content
  replacement: it prevents Radical Gyms & Structures from independently
  generating competing gyms while League Orchestrator owns gym allocation.

## What it may observe, but never replace

The settlement tag contains optional references to BCA village structure IDs.
When BCA is installed, League can predict and register those structures as
ordinary towns. When it is absent, the optional references disappear cleanly.
League does not alter BCA structure sets, templates, pools, loot, or other BCA
data.

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
- The core mod remains useful with only Region Engine and Cobblemon.
- Biome-mod compatibility is additive and pack-owned. League ships no
  per-biome-mod override packs; a pack author can extend
  `league_orchestrator:kanto_biomes` with their own optional datapack.
- A Kanto world always has the vanilla-biome baseline in that tag, so an absent
  biome mod never invalidates the profile.

## Campaign selection and order

Enable any number of non-overlapping campaign packs before creating a world.
The bundled Kanto Classic pack should be above Region Engine's normal mod data
because it supplies the Kanto profile and League definitions. A procedural or
roguelike campaign pack declares its own profile IDs and randomized pools; any
profile with no specialist pack resolves to Frontier.

Region Engine's Atlas itself is not a selectable datapack. An external campaign
pack may add League definitions or additive biome-tag entries, but must not
replace generated worldgen after a world has begun.

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
