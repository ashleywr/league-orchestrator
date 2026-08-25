# Architecture and recorded decisions

This document is the current design baseline. It distinguishes implemented
runtime behavior from planned content capabilities so a datapack does not rely
on a contract that does not yet have a renderer.

## Authority boundaries

| Concern | Owner | Current state |
|---|---|---|
| Continents, regions, and profiles | Region Engine | Live |
| Campaign selection and persistent allocations | League Orchestrator | Live |
| Settlement-start prediction | League, using vanilla placement state | Live |
| Third-party village generation | The third-party provider | Observed only |
| Gym lots and native gym | League | Live |
| RCT trainer/battle data | RCT | Live bridge |
| Town street grammar and building ports | League content runtime | Planned |
| Wilderness route rendering | RoadWeaver, fed by League's graph | Live optional bridge |
| Macro terrain-module assembly | League content runtime | Planned |

## Current event flow

### Server start

League creates/loads `WorldLeaguePlan`, republishes saved RoadWeaver endpoints
and routes, requeues saved eligible RCT anchors, and requests planning of the
spawn atlas site.

### Player-driven planning

At server end tick, League checks each overworld player's atlas site. A site
is planned once. Snapshot capture happens on the server thread; pure placement
calculation runs on a single daemon worker; its result is returned to the
server thread and persisted.

Prediction enumerates only structures in the
`league_orchestrator:settlements` tag. It uses vanilla's structure-set
placement state and noise-biome eligibility without generating chunks. The
current candidate selection is deterministic but is not yet a complete copy of
every mod's weighted structure-selection implementation; content with several
compatible tagged structures in one set requires runtime realization as the
final authority.

### Chunk load

League inspects actual starts in a naturally loaded overworld chunk. A matching
predicted settlement receives its final bounding box, then one gym-placement
request may be queued. A newly loaded gym-anchor chunk also makes a saved RCT
leader eligible for reconciliation. Chunk load never discovers a new town or
forces another chunk to load.

### End tick work

- Player-site planning: cheap duplicate checks; only an unplanned site starts
  an asynchronous prediction.
- Gym placement: one queued town is considered. External structures may force
  load their documented placement area because vanilla `/place structure`
  requires it.
- Provider coordinator: one RoadWeaver or RCT action is executed globally per
  tick, ordered by due time. Delayed work cannot starve ready work.

### Cobblemon spawn callback

League cancels wild spawner output outside the current profile's allowed
species pools. It leaves direct/manual Pokémon creation alone.

## Campaign and addon decisions

- A region profile resolves one campaign on first use and saves that choice;
  later datapack changes can cover untouched profiles but cannot reroll an
  explored profile.
- RCT API and RCT Mod are hard League dependencies. Every non-fallback
  campaign owns a valid RCT series; Frontier is the only intentionally
  series-free fallback.
- Addon APIs and world mutations run server-side through
  `ProviderIntegrationCoordinator`. Background work is immutable planning
  only. See [INTEGRATION_CONTRACT.md](INTEGRATION_CONTRACT.md).
- RCT leaders use durable `PENDING`, `VERIFYING`, `ACTIVE`, and `DEGRADED`
  state. An ambiguous spawn is degraded rather than replayed.
- RoadWeaver is never authoritative for town discovery, campaign topology, or
  progression. It renders selected macro edges only.
- BCA is ARR and is never copied, overridden, or injected into. Its public
  structure IDs can be tagged as settlement candidates when installed.

## Modular-town direction — planned, not yet live

The intended town system is a constrained recursive grammar evaluated as a
finite, seeded graph—not unbounded recursive block placement:

```text
campaign + world seed
  → choose a town palette/module set
  → plan compatible ports, lots, transforms, and bounds
  → save the complete finite plan
  → place bounded work items over later ticks
```

Every authored building/module will declare explicit semantic ports such as
`road`, `footpath`, `water`, or `cave`, plus their local position, facing,
clearance, and optional lead-in/apron. League will transform and save these
ports when it places a template; it will not infer doors from jigsaw blocks or
from a third-party structure's bounding box.

The first planned implementation is two-level:

1. **Town scale:** weighted licensed building/path templates create a random
   but semantically connected town. League owns internal streets and building
   approaches.
2. **Region scale:** selected macro modules—starter towns, routes, coasts,
   forests, mountain passes, caves, and landmarks—form a Kanto-like but new
   regional grammar. Modules require compatible edge sockets and elevation
   contracts. This is not a single flat random pool.

RoadWeaver attaches to the saved outer gateway/apron of a town or macro module,
not to each door. It remains useful for long procedural wilderness routes;
curated internal paths are League-owned templates.

The macro-module terrain renderer is not implemented. The first asset-support
step is registered external structures for gyms; generic licensed NBT/jigsaw
building and macro-module placement need their own data contracts, collision
rules, transform-aware ports, and a safe terrain strategy before they are
enabled.

## Deprecated assumptions

- A town palette is **not** currently a live generic town placer. It is parsed,
  validated campaign data reserved for that implementation.
- RoadWeaver cannot be given a doorway, port facing, or custom obstacle volume
  through its current public endpoint API. Do not model town streets as
  RoadWeaver roads.
- Jigsaw connectors are assembly metadata, not a persistent road-routing API.
  Explicit League ports are required.
- The system does not yet support raw NBT directly as a gym `structure_tag`.
  That field names a registered vanilla structure.
