# League Orchestrator

League Orchestrator is a server-side Fabric 1.21.1 campaign layer for
Cobblemon worlds. Region Engine owns continental geography; League owns the
saved campaign plan: settlement identity, gym allocation, route topology, and
provider-owned leader state.

League has hard runtime dependencies on Region Engine, Cobblemon, RCT API, and
Radical Cobblemon Trainers. Every League world can therefore support trainers;
an individual campaign may still be series-free or have no placed gyms.

It does not copy or replace third-party worldgen/assets. BCA and other
structure mods may be observed through public structure IDs, while campaign
content is supplied by normal datapacks.

## Current runtime

1. At server start, League opens its saved world plan, requeues saved roads and
   eligible leaders, and predicts the atlas site containing spawn.
2. As players enter continental atlas sites, a worker predicts tagged vanilla
   structure starts from the actual structure-placement state. This reads no
   terrain chunks or heightmaps.
3. The server thread saves planned towns, gym allocations, and a deterministic
   town-to-town route graph. It gives RoadWeaver only that graph's endpoints
   and edges.
4. When a relevant chunk naturally loads, League reconciles a predicted town
   with its real structure bounding box. It queues a gym only for that already
   realized town.
5. At most one queued gym placement and one total optional-provider action are
   processed per server end tick. RCT leaders require an already-loaded anchor
   chunk and nearby player; they never force terrain generation.

The one intentional exception to prediction-only behavior is physical external
gym placement: vanilla `/place structure` needs an adequately loaded placement
area. This is deferred to a single gym job and may still be expensive. It is
not part of town prediction or RCT leader recovery.

League filters normal wild Cobblemon spawns using Region Engine's regional
allow/deny species pools. It does not yet implement route-level spawn
replacement, level scaling, rewards, legendary triggers, or generic town
building placement.

## RoadWeaver's role

League is authoritative for *which* places connect. RoadWeaver is an optional
macro-route renderer for *how* a route crosses open terrain. Its public API
accepts endpoints and connections, not building doors, port facings, or custom
obstacle bounds. League therefore does not use it for town streets.

The planned town system will own semantic building ports, aprons, and internal
streets. RoadWeaver will connect only safe outer town gateways. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the resulting boundary.

To keep League authoritative for a new world, disable RoadWeaver's autonomous
structure prediction and dynamic planning in its config. League's own bridge
uses only RoadWeaver's public `registerStructureEndpoint` and
`ensureConnection(..., false)` calls.

## Content packs

Campaigns are regular server datapacks, resolved per Region Engine profile and
persisted once a profile is first used. A specialist campaign supplies its own
RCT series; `league_orchestrator:frontier` is the procedural fallback for all
otherwise-unclaimed profiles.

External structures must be original or redistribution-permitted content. A
pack should keep an `ATTRIBUTION.md` recording each asset's author, source,
license/permission, and required credit. A raw NBT template is not enough for
the current external-gym path: it must be exposed as a registered vanilla
structure. Future town modules will accept licensed NBT/jigsaw assets through
their own explicit building/module definitions.

- [CONTENT_PACK_FORMAT.md](CONTENT_PACK_FORMAT.md) — implemented data formats
  and reserved contracts.
- [DATAPACK_POLICY.md](DATAPACK_POLICY.md) — ownership, provider, and asset
  boundaries.
- [INTEGRATION_CONTRACT.md](INTEGRATION_CONTRACT.md) — server-thread and
  provider-failure rules.
- [ARCHITECTURE.md](ARCHITECTURE.md) — decisions, current event flow, and the
  modular-town direction.

## Operators

`/league info`, `/league site`, and `/league town nearest` expose the saved
campaign/town state. `/league integration status` reports the bounded provider
queue. A degraded gym leader can be inspected with
`/league town leader <id> status` and explicitly reconciled again with
`/league town leader <id> reset`.

## Development

```powershell
.\gradlew.bat clean build --no-daemon -Pcobblemon_dev_jar="C:\path\to\Cobblemon-fabric-1.7.3+1.21.1.jar"
```

The adjacent Region Engine build and supplied Cobblemon jar are development
contracts only; neither is embedded in the output.
