# League Orchestrator

League Orchestrator is a server-side Fabric 1.21.1 campaign layer for
Cobblemon worlds. Region Engine owns continental geography; League owns the
saved campaign plan: settlement identity, gym allocation, route topology, and
provider-owned leader state.

League has hard runtime dependencies on Region Engine, Cobblemon, RCT API, and
Radical Cobblemon Trainers. Every League world can therefore support trainers;
an individual campaign may still be series-free or have no placed gyms.

It does not copy third-party assets. The current BCA pilot uses BCA's public
registered village IDs and jigsaw definitions at League-owned placements while
the `cobblemon-additions` provider is installed; it does not package BCA data
or assets.

## Current runtime

1. A new League world records vanilla's initial spawn, then reserves the
   nearest predicted town in that atlas as its starter town. Existing League
   worlds retain their saved shared spawn unchanged.
2. League's pure `planned_town` placement is the active `minecraft:villages`
   placement. Minecraft and League therefore calculate the same BCA start
   chunks from the seed and Region Atlas, without a terrain scan. The bundled
   Kanto pilot requests 10 candidates per Atlas continent, with a 16-chunk
   safety separation. Region size can change without requiring pack authors
   to derive an unrelated global spacing value.
3. League warms one planned start chunk at a time through Minecraft's normal
   ticket/chunk pipeline. BCA then performs its normal final jigsaw validation.
4. After BCA confirms a real starter town, League validates an outdoor apron
   chunk with vanilla's respawn logic, moves the shared spawn there, and uses
   that same apron as the first RoadWeaver endpoint. It otherwise preserves
   vanilla spawn and tries the next predicted town. Other realized towns then
   form the town-to-town route graph; a rejected jigsaw cannot become a
   phantom road destination.
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

## Server configuration

`config/league-orchestrator.json` is written on first start. It holds server
policy - how a campaign is run - and never campaign content. Values are read
once at startup and clamped to safe ranges on load.

| Key | Default | Meaning |
|---|---|---|
| `mount` | `main_world` | Where League content generates. `instanced_dimension` is reserved for a separate League dimension and is refused with a warning until it is implemented. |
| `serverActionBudgetNanos` | `5000000` | Per-tick budget for optional RoadWeaver/RCT work. Exceeding it logs a slow-action warning; it never cancels work in flight. |
| `maxStartupTownCandidates` | `3` | Predicted towns a new world will try before leaving the shared spawn where vanilla put it. Each attempt loads chunks. |
| `maxStartupRouteCandidates` | `12` | Predicted towns considered for the first RoadWeaver endpoint. |
| `externalStructureLoadRadius` | `128` | Blocks force-loaded around an external gym structure before placement. |
| `rctLeaderActivationDistance` | `192.0` | How close a player must be before a saved RCT leader is reconciled. Never forces chunk loading. |

Gym allocation ratios, town tiering, and encounter bands are deliberately absent.
Those are campaign design: a server tuning them would silently unbalance a pack
its author tested. They belong to content packs.

## RoadWeaver's role

League is authoritative for *which* places connect. RoadWeaver is an optional
macro-route renderer for *how* a route crosses open terrain. Its public API
accepts endpoints and connections, not building doors, port facings, or custom
obstacle bounds. League therefore does not use it for town streets.

The planned town system will own semantic building ports, aprons, and internal
streets. RoadWeaver will connect only safe outer town gateways. Its built-in
roadside-village generator stays disabled; future original/permitted PokéCenter
and shop templates use its smaller `roadside` service-stop type instead. See
[ARCHITECTURE.md](ARCHITECTURE.md) and
[the service-stop template](examples/roadweaver-service-stops/README.md).

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
