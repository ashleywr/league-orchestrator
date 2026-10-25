# League Orchestrator

League Orchestrator is a server-side Fabric 1.21.1 campaign layer for
Cobblemon worlds. It owns the persistent campaign plan: settlement roles and
tiers, gyms, trainer allocation, encounter semantics, and the endpoints it
offers to RoadWeaver. It does not redistribute third-party structures or
assets.

Terra Kanto is a finite, seeded, Kanto-inspired continent with real Minecraft
and Regions Unexplored biome IDs plus terrain-aware League settlement
placement. The original `Mod Test` route mounts it as the overworld and uses
RoadWeaver. **Cobblemon Koyo League** instead mounts it as a separate campaign
dimension, keeping the normal Koyo overworld persistent and RoadWeaver-free.

See [KANTO_TERRA.md](KANTO_TERRA.md) for the complete installation, fresh-world,
validation, tuning, visual, and troubleshooting guide.

## Choosing a world type

Choose the world type once when creating a world. Terrain and the initial
League plan are saved, so changing generator or settlement settings means
creating a fresh test world.

| World type | Use it for |
|---|---|
| `Kanto League Dimension` (`league_orchestrator:kanto_dimension`) | The Cobblemon Koyo League route: normal Koyo hub overworld plus a Terra `kanto:kanto` League dimension. RoadWeaver, when installed, is tagged for Kanto only. |
| `Terra Kanto` (`terra:kanto/kanto`) | The legacy Mod Test route: finite Terra overworld, League towns, and optional RoadWeaver routes. |
| `League Dimension (Vanilla)` | A separate League dimension over vanilla terrain, retained for compatibility. |
| `Normal` | Vanilla/modpack terrain. League can still run its non-Terra fallback behavior. |

`Atlas World` is only available when the optional Region Engine integration
provides it. Region Engine is no longer a hard dependency of League
Orchestrator; Terra Kanto works without it. OpenTerrainGenerator is likewise
not required for the active Kanto route.

The compact `generator:terra:kanto/kanto` text in Minecraft's create-world UI
is the legacy Terra-overworld preset identifier, not a seed or a command to
type. For Cobblemon Koyo League, use the runtime preset
`league_orchestrator:kanto_dimension` (for example through AutoMine); the hub
overworld remains normal and `/league travel` enters or leaves Kanto.

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
- RoadWeaver is an optional legacy renderer. Cobblemon Koyo League intentionally
  omits it; League towns and gameplay do not require roads to be installed.

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

<!-- ashley-release-information -->

## Ashley's builds

This is an original project by Ashley Wijnen-Riems. Original project code is offered under the [MIT License](LICENSE). Third-party material retains its own license and attribution.

Provided **AS IS**, without warranty of any kind. Maintenance, updates and support are best effort; compatibility with every modpack is not guaranteed.

### Downloads and installation

Download a runtime `.jar` from [GitHub Releases](https://github.com/ashleywr/league-orchestrator/releases). Use the Minecraft version and loader named in that release and install its required dependencies. Do not install `-sources`, `-dev`, `-slim`, `-common` or test JARs as the mod.

Close Minecraft, back up your world, and place the matching runtime JAR in the instance's `mods/` directory. Replace older versions of this same mod rather than loading duplicates. For content mods, install matching versions on the server and its clients unless the release explicitly says client-only or server-only.

### Building and reporting problems

Use Java 21 and the checked-in Gradle wrapper:

```powershell
.\gradlew.bat build
```

Build outputs are under `build/libs/` or each loader subproject's `build/libs/`. Some projects require the local development dependencies described above; those dependencies are not included in the download.

For a problem report, include the exact release filename, Minecraft and loader versions, relevant mod versions, `logs/latest.log`, and a crash report if one was written. Remove private information before sharing logs. Remove the JAR to uninstall; worlds containing this mod's blocks, entities or world generation may require restoring a backup.
