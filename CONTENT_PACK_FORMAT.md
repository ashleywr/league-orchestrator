# League campaign data contracts

League Orchestrator supplies the runtime and the saved world plan. A campaign
datapack supplies definitions for one or more Region Engine profiles. The first
campaign resolved for a profile is written to the world plan and is not
replaced by later datapack reloads.

## Status of these contracts

All definitions below are parsed and validated. Only the following are active
world behavior today: campaign selection, species-pool spawn filtering,
settlement prediction, gym allocation/placement, RoadWeaver macro endpoints,
and RCT gym-leader anchoring.

| Definition | Runtime status |
|---|---|
| Campaign | Active |
| Species pool | Active allow/deny filter only |
| Gym archetype | Active |
| Town palette | Reserved for the generic town/module placer |
| Route profile | Reserved for route policy/rendering selection |
| Encounter profile | Reserved; does not replace Cobblemon spawns yet |
| Legendary encounter | Reserved; no trigger/placement adapter yet |
| Trainer definition and reward definition | Reserved provider contracts |

See [ARCHITECTURE.md](ARCHITECTURE.md) for the planned modular-town and
macro-module system. Do not ship a campaign that depends on a reserved
definition having runtime effects yet.

## Assigning campaigns to regions

The campaign itself can live in any data namespace. It declares the Region
Engine profiles it owns; no central selector needs editing when a new campaign
datapack is added.

`data/example/league_orchestrator/campaigns/frontier.json`

```json
{
  "id": "example:frontier",
  "mode": "procedural",
  "seed_salt": "frontier-season-one",
  "gym_pool": "frontier_badges",
  "rct_series": "frontier_season_one",
  "town_names": ["Alder", "Cedarfall", "Stonebridge"],
  "region_profiles": ["region_engine:johto", "region_engine:sinnoh"],
  "town_palettes": ["example:temperate_towns", "example:coastal_towns"],
  "route_profiles": ["example:main_road", "example:mountain_track"],
  "encounter_profiles": ["example:early_routes", "example:late_routes"],
  "legendary_encounters": ["example:storm_summit"],
  "trainers": ["example:cliff_leader"],
  "rewards": ["example:cliff_badge_money"],
  "requires": ["moogs_structures"]
}
```

The built-in `league_orchestrator:frontier` campaign is procedural and marked
as the fallback. It applies to every profile that has no region-specific
campaign, so the whole Atlas can run randomized rules immediately. A campaign
with `"region_profiles": ["region_engine:kanto"]` supersedes Frontier for
Kanto only. If two packs claim the same profile, highest `priority` wins; ties
use a stable campaign-ID order. Avoid ties for intentional pack behavior.

`mode` is either `fixed` or `procedural`. Fixed campaigns identify a authored
set of outcomes. Procedural campaigns use the same definitions as weighted
pools and constraints. Future resolution is always derived from the world seed,
the campaign ID, and `seed_salt`, then persisted in the plan.

`requires` is a hard requirement for this *campaign*, not for the generic core
mod. If one of those mod IDs is absent, the campaign is unavailable and the
profile resolves to another valid campaign or the Frontier fallback. This is
the right boundary for packs that depend on
Moog's Structure Lib towns, CobbleFurnies decor, Mega Showdown rewards,
CobbleDollars rewards, or SimpleTMs rewards. RCT API and RCT Mod are hard
dependencies of League Orchestrator itself, so campaigns do not need to repeat
them in `requires`.

### Named towns and zone identity

`town_names` is an optional array of display names for towns generated under a
campaign. League selects one deterministically from the world seed, campaign
`seed_salt`, and stable settlement ID, then persists that result with the
world plan. Changing a datapack later therefore never renames an explored
town. Duplicate display names are permitted; content packs that need every
town to be uniquely named should provide a sufficiently large pool.

Each saved League town is mirrored into Region Engine's generic named-place
registry. Its stable ID is implementation-owned and must not be reconstructed
from its visible name; use the place's `town` and `settlement` tags, or its
resolved ID, for spawn/event integrations. A `PREDICTED` town does not appear
in normal zone lookup. Once BCA or another generator confirms it, it becomes a
`REALIZED` zone using the actual structure bounds plus a 48-block town fringe.
A rejected candidate becomes
a small `FALLBACK` `route_stop`, never a town spawn zone.

### Town count belongs to the structure set

The League placement accepts a semantic target in the normal Minecraft
structure-set JSON, rather than a fixed grid spacing:

```json
"placement": {
  "type": "league_orchestrator:planned_town",
  "salt": 31415926,
  "towns_per_continent": 10,
  "minimum_separation": 16
}
```

`towns_per_continent` is the desired number of seed-stable candidates for each
Region Engine continental site. The placement samples only that site’s actual
land footprint, with `minimum_separation` measured in chunks. BCA can still
reject an individual candidate during its normal terrain/jigsaw checks, so the
final realized count can be lower. Minecraft’s worldgen, League’s fallback
predictor, and RoadWeaver’s regional planner all call this same pure placement
function. Three of the requested slots are deliberately sampled from the
continent’s inner third so the normal starter-town bootstrap has nearby,
ordinary BCA candidates before it considers the outer settlement ring.

Every non-fallback campaign must declare `rct_series` and must include the
matching RCT series resource. League validates both `rctmod`/`rctapi` and the
resource during reload; an invalid series makes that campaign unavailable.
Frontier is the sole exception because it is deliberately series-free, not
because it can run without RCT.

`data/rctmod/series/frontier_season_one.json`

```json
{
  "title": { "text": "Frontier Season One" },
  "description": { "text": "An original League campaign series." },
  "difficulty": 5,
  "requiredSeries": []
}
```

This is normal RCT extension data, not a replacement for RCT's bundled series.
The saved world plan keeps the selected series ID beside the campaign for each
Region Engine profile, allowing trainer adapters to use RCT's series graph
without inferring a region from a trainer ID.

## Town palettes

`data/example/league_orchestrator/town_palettes/temperate_towns.json`

```json
{
  "id": "example:temperate_towns",
  "region_profiles": ["region_engine:kanto", "region_engine:johto"],
  "generator": "moogs_structures",
  "structure_pools": ["example:temperate_houses", "example:temperate_civic"],
  "port_types": ["road", "water"],
  "requires": ["moogs_structures", "cobblefurnies"]
}
```

`structure_pools` must be original or properly licensed pack content. A town
palette is currently declarative: `generator` names the intended provider or
renderer but League does not invoke Moog's Structure Lib or another town API
yet. The future generic placer will use explicit authored building/module ports
with positions, facings, clearance, and road aprons. It will not guess a door
from a jigsaw connector or inside BCA/another third-party template. Until that
placer exists, a town palette does not place anything.

## Route and encounter profiles

`data/example/league_orchestrator/route_profiles/main_road.json`

```json
{
  "id": "example:main_road",
  "region_profiles": ["region_engine:kanto"],
  "renderer": "roadweaver",
  "port_type": "road",
  "minimum_level": 8,
  "maximum_level": 18,
  "encounter_profile": "example:early_routes"
}
```

`data/example/league_orchestrator/encounter_profiles/early_routes.json`

```json
{
  "id": "example:early_routes",
  "species_pool": "example:early_species",
  "minimum_level": 8,
  "maximum_level": 18,
  "contexts": ["route", "town_edge"]
}
```

These definitions are a level-and-context contract. They do not replace
Cobblemon's spawn JSONs yet. They are intentionally not a parallel spawn
system; a future bridge must use an exact supported Cobblemon API.

## Trainers and rewards

`data/example/league_orchestrator/trainers/cliff_leader.json`

```json
{
  "id": "example:cliff_leader",
  "provider": "rctmod",
  "team_pools": ["example:cliff_leader_team"],
  "minimum_level": 20,
  "maximum_level": 24,
  "requires": []
}
```

`data/example/league_orchestrator/rewards/cliff_badge_money.json`

```json
{
  "id": "example:cliff_badge_money",
  "provider": "cobbledollars",
  "kind": "currency",
  "value": "league_win",
  "amount": 1200,
  "requires": ["cobbledollars"]
}
```

The same reward contract can identify a `simpletms` TM or a
`mega_showdown` item. The provider adapter, rather than the campaign JSON,
will perform the grant and record it as claimed.

Gym archetypes can reference the resulting reward IDs through an optional
`"rewards": ["example:cliff_badge_money"]` array. This adds a reward to a
physical gym without coupling the gym structure to a particular reward mod.

### Anchoring an RCT gym leader

The gym archetype's optional `trainer` is an exact RCT trainer ID, not a
League resource ID. That trainer's RCT data must include the campaign series:

```json
{ "series": ["frontier_season_one"] }
```

League checks this membership before spawning anything. For League's native
`league_orchestrator:village_gym`, the leader is anchored to its built-in dais.
For an external gym structure, the archetype must declare its own local leader
position. `structure_tag` must be a registered vanilla structure ID, not a
bare NBT file or template-pool ID:

```json
{
  "pool": "frontier_badges",
  "region_profiles": ["region_engine:johto"],
  "structure_tag": "example:cliff_gym",
  "trainer": "cliff_leader",
  "leader_offset": [0, 1, 4]
}
```

After a player comes near the placed gym, League calls RCT's public targeted
spawn API at that exact saved position, sets it as the trainer's home, and
makes the entity persistent. The leader UUID and anchor are saved with the
town. League never calls RCT's random nearby-spawn path for a gym leader. A
generic rotated/mirrored multi-port building contract is planned separately;
do not treat `leader_offset` as that future port system.

### Trainer progression and level pacing belong to RCT, not League

League does not implement its own level-range or gym-ordering system. RCT
already owns both, and League's job stops at referencing an exact RCT
trainer ID:

- **Wild Pokémon level ranges** are Cobblemon's own biome/structure-keyed
  `spawn_pool_world` entries. A route or encounter profile's job is to
  describe context (which biome, which renderer), not to carry a parallel
  level-range system.
- **Trainer level pacing and battle ordering** are RCT's `requiredDefeats`
  graph (`data/<namespace>/rctmod/mobs/trainers/single/<id>.json`) and its
  recursive `trainerLevel` resolution, which automatically clamps a
  trainer's effective level to be at least as hard as every prerequisite.
  `requiredDefeats` is a list of alternative-sets: each inner array is an
  "any one of these" requirement, and the outer list is "all of these
  requirements must be satisfied." Two trainers with no edge between them
  are simultaneously available — RCT does not require a strict chain.
  `TrainerMob` already refuses to battle a player who has not met a
  trainer's requirements, so League never needs to gate access itself.

A campaign is free to give its gyms a non-linear structure by wiring
`requiredDefeats` into tiers instead of a single chain. The bundled Kanto
Classic pack does this: `data/rctmod/mobs/trainers/single/` defines three
gym leaders with no prerequisites (open immediately, any order), two that
require defeating any one of those three, two more that require any one
from each of the first two tiers, and a final gym that requires any one
from each of the middle two tiers. The physical town each gym lands in is
still chosen independently by League's own seeded, weighted `gym_pool`
allocation (see [DATAPACK_POLICY.md](DATAPACK_POLICY.md)) — RCT's graph
governs when a gym will fight you, League's allocation governs where it is.

`scripts/generate_gym_progression.py` generates this tier structure instead
of hand-authoring it: point it at a directory of existing progression files
and a target trainer count, and it regenerates only their `requiredDefeats`
fields (every other authored field, including team data elsewhere, is left
alone). It never talks to a running server; its output is a normal datapack
to commit, ship, or drop into a world's own `datapacks/` folder. RCT itself
supports picking up regenerated trainer data without a server restart —
`RctGymLeaderBridge.reloadTrainerData` (exposed as `/league trainers reload`)
calls RCT's own trainer reload, which reconciles already-spawned leaders
against the refreshed graph rather than orphaning them.

## Legendary encounters

`data/example/league_orchestrator/legendary_encounters/storm_summit.json`

```json
{
  "id": "example:storm_summit",
  "species": "cobblemon:zapdos",
  "trigger": "summit_shrine",
  "region_profiles": ["region_engine:kanto"],
  "requires": []
}
```

Legendary definitions identify a one-time planned encounter. Their eventual
location, activation, and claim state belong to `WorldLeaguePlan`, preventing
reloads or a second explorer from rerolling or duplicating a legendary.

## Current integration status

The contracts and provider validation are live. Generic settlement prediction,
the saved macro road graph, native/external gym placement, RoadWeaver endpoint
rendering, and RCT's fixed gym-leader bridge are active. Moog's Structure Lib,
CobbleFurnies, Mega Showdown, CobbleDollars, and SimpleTMs are registered as
known provider capabilities, but their actual content adapters are not yet
implemented. A pack should only list a provider in `requires` once it uses
that provider's implemented feature.
