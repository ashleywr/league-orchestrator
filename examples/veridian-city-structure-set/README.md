# Veridian city structure set

This is the start of League-owned, Minecraft jigsaw content for a future
Veridian City theme. It contains only lawfully supplied League assets and no
external templates, pools, entities, or namespaces.

## Current template

```text
data/league_orchestrator/structure/veridian/pokecenter.nbt
```

The matching pool is:

```text
data/league_orchestrator/worldgen/template_pool/veridian/one_off.json
```

Use it from a future League-owned city-center jigsaw with:

```text
name: league_orchestrator:veridian_pokecenter_connector
target: league_orchestrator:veridian_pokecenter
pool: league_orchestrator:veridian/one_off
```

The Pokécenter's copied bottom marker targets
`league_orchestrator:veridian/decor`. That pool is intentionally empty today,
so the marker resolves to dirt. Add only League-owned or properly licensed
decor elements when the Veridian set grows.

The old Nurse Joy socket is currently plain `cobblemon:apricorn_planks`; this
set therefore does not place a third-party NPC. When this building gains a
League-owned `healer_attendant` marker, the bundled default will be a
stationary Cobblemon nurse villager placed by League's runtime marker
processor. The marker is semantic rather than model-specific, so a later
content pack can select a different attendant without rebuilding the template.
Attribution for the extracted building is in [`ATTRIBUTION.md`](ATTRIBUTION.md).
