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

RoadWeaver receives only saved campaign endpoints and explicit graph edges.
Endpoint registration and `ensureConnection(..., false)` are separate,
coordinated provider operations; no RoadWeaver reflection runs at town-planning
or worldgen call sites. A RoadWeaver exception disables further calls for that
server session while League retains the graph in `WorldLeaguePlan` for a later
restart/reconciliation. League never calls RoadWeaver's synchronous generation
mode.

RoadWeaver's public endpoint API does not accept a doorway, facing, custom
clearance volume, or jigsaw socket. It is therefore a macro-route renderer,
not League's town-street generator. Future content must place a deterministic
gateway/apron and give RoadWeaver only the outer gateway position; League's
planned town grammar owns internal paths and semantic building ports.

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
