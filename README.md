# League Orchestrator

League Orchestrator is a server-side Fabric 1.21.1 campaign layer for
Cobblemon worlds. It owns the persistent campaign plan: settlement roles and
tiers, gyms, trainer allocation, encounter semantics, and the endpoints it
offers to RoadWeaver. It does not redistribute third-party structures or
assets.

The current Kanto test route uses **Terra Kanto**: a finite, seeded,
Kanto-inspired continent with real Minecraft and Regions Unexplored biome IDs,
terrain-aware League settlement placement, and RoadWeaver macro routes. It is
the supported world type for the shared `Mod Test` instance.

See [KANTO_TERRA.md](KANTO_TERRA.md) for the complete installation, fresh-world,
validation, tuning, visual, and troubleshooting guide.

## Choosing a world type

Choose the world type once when creating a world. Terrain and the initial
League/RoadWeaver plan are saved, so changing generator, settlement, or route
settings means creating a fresh test world.

| World type | Use it for |
|---|---|
| `Terra Kanto` (`terra:kanto/kanto`) | The active Kanto campaign test: finite continent, Terra terrain/hydrology, real biome registry entries, League towns, and RoadWeaver routes. |
| `League Dimension` | A separate League dimension over vanilla terrain. It is retained for compatibility, not for Kanto terrain testing. |
| `Normal` | Vanilla/modpack terrain. League can still run its non-Terra fallback behavior. |

`Atlas World` is only available when the optional Region Engine integration
provides it. Region Engine is no longer a hard dependency of League
Orchestrator; Terra Kanto works without it. OpenTerrainGenerator is likewise
not required for the active Kanto route.

The compact `generator:terra:kanto/kanto` text in Minecraft's create-world UI
is the world-preset identifier, not a seed or a command to type. Select the
**Terra Kanto** entry from the normal World Type list; the field only displays
the selected preset in Minecraft's native format.

## Kanto in brief

- The bundled Kanto campaign is global and applies automatically. Do not use
  AutoMine or a world datapack to activate it.
- Kanto keeps actual biome IDs such as `minecraft:forest` and
  `regions_unexplored:wisteria_grove`; `terra:kanto` is not an artificial
  biome replacing them. F3 and ordinary mod compatibility therefore see the
  real selected biome.
- Campaign and Cobblemon rules use semantic biome tags such as
  `#league_orchestrator:kanto_forest`, rather than hard-coding one biome ID.
  A modpack author can provide the same biome/spawn contract under their own
  namespace in a datapack—no Java recompilation is needed once their
  geographic provider exposes that campaign profile.
- RoadWeaver consumes League's realized town and landmark endpoints and owns
  terrain sampling, route deduplication, and road placement. League owns
  which places are meaningful; RoadWeaver owns how the wilderness road crosses
  terrain.

## Content packs and integrations

Campaigns are normal server datapacks. A specialist campaign supplies its own
trainer data; `league_orchestrator:frontier` is the generic fallback. The
[region content-pack template](examples/league-region-content-pack-template/README.md)
shows how to create a separately named location and its biome/spawn semantics.

- [CONTENT_PACK_FORMAT.md](CONTENT_PACK_FORMAT.md) — implemented campaign
  data formats and semantic biome contracts.
- [DATAPACK_POLICY.md](DATAPACK_POLICY.md) — campaign ownership, activation,
  provider, and asset boundaries.
- [INTEGRATION_CONTRACT.md](INTEGRATION_CONTRACT.md) — RoadWeaver, Terra, and
  optional-provider threading/failure rules.
- [ARCHITECTURE.md](ARCHITECTURE.md) — authority boundaries and implemented
  versus planned work.

## Operators

`/league info`, `/league site`, and `/league town nearest` expose saved
campaign and town state. `/league integration status` reports bounded provider
work. `/league town leader <id> status` inspects a trainer bridge and
`/league town leader <id> reset` explicitly returns a degraded trainer marker
to `PENDING`.

## Development

```powershell
.\gradlew.bat clean build --no-daemon -Pcobblemon_dev_jar="C:\path\to\Cobblemon-fabric-1.7.3+1.21.1.jar"
```

Build artifacts are not deployment artifacts until the remapped runtime JAR is
hash-verified in the selected Prism instance. See the deployment checklist in
[KANTO_TERRA.md](KANTO_TERRA.md).
