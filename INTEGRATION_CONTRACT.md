# Addon integration contract

League Orchestrator treats every optional addon as an untrusted server-side
boundary. An addon may be absent, load invalid content, change its public API,
or throw after it has already changed the world. None of those conditions may
force chunk generation, block a tick indefinitely, or be retried as though no
side effect happened.

## Authority and threading

- Background work may prepare immutable identifiers and coordinates only. It
  must not access a Minecraft server, level, chunk, entity, registry, or addon
  API.
- Every addon call and world mutation goes through
  `ProviderIntegrationCoordinator` on the server thread. The coordinator runs
  at most one provider action per end tick.
- The coordinator uses a bounded (1,024) due-time priority queue. It dedupes
  requests by durable intent key, returns `false` on capacity instead of
  dropping work, and scopes circuit breakers to `(provider, operation)`.
- A persistent owner such as `WorldLeaguePlan` retains desired state. It may
  resubmit from a later chunk-load or server-start reconciliation event.
- Provider execution has a 5 ms telemetry budget. This records slow calls; it
  cannot safely preempt a mod already executing on the server thread.

## RCT gym-leader state machine

Each authored leader marker is saved with one of these states:

- `UNASSIGNED`: no leader marker or trainer is declared.
- `PENDING`: desired state, awaiting only safe preconditions (loaded anchor
  chunk and a nearby player).
- `VERIFYING`: League persisted that it entered RCT's spawn API. On restart or
  failure it performs exact reconciliation and will not blindly call spawn a
  second time.
- `ACTIVE`: League has an RCT trainer UUID at the exact saved home position.
- `DEGRADED`: content is invalid, a provider result is ambiguous, or an
  unexpected provider error occurred. Automatic spawning stops.

The RCT adapter uses `getChunkNow`, never `getChunk`, so it does not generate
or synchronously load terrain. It checks RCT's active trainer set by both
trainer ID and exact home position before a replacement spawn. If a call has an
ambiguous result, or if a restart finds a `VERIFYING` marker without an exact
trainer, the marker becomes `DEGRADED`; duplicating an NPC is less acceptable
than requiring a repair.

Operators can inspect the queue with `/league integration status`, inspect a
nearby atlas-town marker with `/league town leader <id> status`, and explicitly
return a degraded marker to `PENDING` with `/league town leader <id> reset`.
Reset does not bypass exact reconciliation.

## RoadWeaver bridge

For the active Terra Kanto world type, League first exposes a Terra-compatible
geography bridge and validates bounded settlement candidates against the active
generator. This is deliberately not an independent continent generator:
Terra owns terrain and biome identity, while League owns campaign site roles
and accepts only buildable locations. A failed candidate may use a bounded
wider search or be skipped. It must never be force-placed on an invalid height,
inside open cave air, or at a hard-coded coordinate selected for one seed.

RoadWeaver owns regional structure discovery, topology planning, terrain
sampling, route deduplication, and road generation. League does not run a
second atlas-wide discovery pass when RoadWeaver exposes the placement-adapter
API. Instead, League registers a pure enumerator for
`league_orchestrator:planned_town`; RoadWeaver applies its normal structure-set
selection and its one regional planning pass to those candidates.

The adapter runs only seed/config/Atlas math and may not read chunks, saved
data, registries, or provider APIs. RoadWeaver's predictor mirrors Minecraft's
weighted structure-set roll before applying its whitelist and biome filter, so
a BCA variant is not substituted merely because it is the first configured
structure. Minecraft/BCA remains final authority for a terrain-sensitive
jigsaw start; League reconciles actual bounds after chunk generation.

On a fresh world, RoadWeaver performs that regional pass after its server
worker lifecycle is ready, then invokes League with its persisted BCA candidate
set before it begins initial road generation. League may use only that callback
to reserve and synchronously try the starter town. It must not race the pass
with a local predictor or publish duplicate endpoints. If every BCA jigsaw
candidate rejects, the shared spawn is left unchanged and the warning is
explicit; no phantom town or road endpoint is recorded.

When an explored predicted start chunk contains no League settlement start,
League records the BCA rejection once and places a small survey-post marker at
that road terminus. The marker is server-side, survives restart, and removes
the failed candidate from starter and warmup retries. This is the route-stop
fallback: roads may end at a deliberate piece of world fiction, never an
unexplained absent town.

For an older RoadWeaver build without the adapter API, League retains the
realized-endpoint fallback: endpoint registration and
`ensureConnection(..., false)` are coordinated provider operations. League
never calls RoadWeaver's synchronous generation mode.

RoadWeaver's public endpoint API does not accept a doorway, facing, custom
clearance volume, or jigsaw socket. It is therefore a macro-route renderer,
not League's town-street generator. Future content must place a deterministic
gateway/apron and give RoadWeaver only the outer gateway position; League's
planned town grammar owns internal paths and semantic building ports.

### Terra sampler and route readiness

RoadWeaver must use its Terra-native terrain sampler when the active generator
is Terra. The OpenCL density evaluator is valid only for vanilla
`NoiseBasedChunkGenerator`; routing Terra through it would make the planned
road disagree with the generated surface. CPU use for Terra is therefore an
expected compatibility mode, not a recoverable GPU failure.

Initial planning is complete only after RoadWeaver has either emitted its
completion event or persisted non-empty planned routes whose recorded
connections are all completed. A spawn-area progress value or a launcher
process alone is not a readiness signal. This completion rule is used by the
fresh-world benchmark workflow.

## Provider adapter rules

- Provider-specific reflection is cached and resolved only on the server
  thread. Do not reflect into private members or copy addon implementation
  code.
- Expected readiness conditions may return a delayed retry. Invalid content,
  missing hard requirements, ambiguous side effects, and unexpected exceptions
  must persist a degraded owner state and stop automatic replay.
- Add a distinct `operationId` for each side-effecting provider action. Do not
  use a provider-wide circuit breaker for unrelated operations.
- A bridge must define creation, reload, chunk unload/load, provider absence,
  entity disappearance, disablement, and operator-repair behavior before it
  owns persistent world state.
