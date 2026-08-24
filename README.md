# League Orchestrator

League Orchestrator is a standalone Fabric 1.21.1 Cobblemon campaign layer. It
requires Region Engine for deterministic continent/profile context and Cobblemon
for wild-Pokémon spawn enforcement. It intentionally does **not** include a
trainer battle engine, structure library, badge artwork, or Koyo branding.

## Current vertical slice

- Loads datapack-defined regional species pools and gym archetypes.
- Cancels normal wild Cobblemon spawns that are not allowed by the active
  Region Engine profile's `cobblemon.allow_species_tags` / `deny_species_tags`.
- Treats an unresolved allow pool as a safe pass-through. A modpack can ship
  Region Engine profiles before it ships the matching Pokémon content data.
- Creates one saved, deterministic allocation ledger per world. Calling the
  allocation service for a `(atlas site, slot, pool)` returns the same gym on
  every later call, and a selected gym is removed from that pool within its
  atlas site.
- Registers named town anchors and automatically attempts one gym beside each
  generated BCA town. The first structure bridge uses vanilla's `/place
  structure` command against a datapack-declared structure ID, so it can place
  Rad Gyms without a compile-time dependency on Rad Gyms.
- Provides `/league info`, `/league site`, `/league claim <pool>`, and the
  operator town commands below.

The automatic BCA test bridge waits for a completed town start, then queues one
gym placement per server tick. It chooses a deterministic lot 128 blocks beyond
one edge of the town's saved bounding box and preloads the bounded external
structure area before using vanilla placement. This deliberately favors a
visible, safe “town edge” result over silently overwriting BCA houses. BCA
village starts are discovered automatically when their start chunk loads; the
saved anchor records its real generated bounding box as the town zone. When
RoadWeaver is installed, its public API registers each successfully placed gym
at the actual post-placement coordinate and queues a short town-to-gym spur;
Rad Gyms must not be added to RoadWeaver's static structure prediction list.
RCT will be the first trainer bridge, using its public API rather than copied
implementation.

## Town gym workflow

Natural gym worldgen should be disabled in Structurify when using this flow.
RoadWeaver should connect town structures only; it should not treat gyms as
separate road endpoints.

For a generated BCA village, no registration command or placement command is
needed. League Orchestrator observes the completed BCA structure start and
records its bounding box on the server thread. A deterministic two-thirds of
towns are eligible for a unique `kanto_badges` gym at their edge; the rest stay
ordinary settlements and RoadWeaver destinations. The initial Kanto pool
contains eight official badge slots, so a town never receives a duplicate.

Stand inside the town and run this diagnostic command:

```mcfunction
/league town nearest
```

It prints the stable generated town ID and lets an operator inspect the saved
anchor. The manual command remains available to place a gym at a chosen plot if
the automatic lot needs adjustment:

```mcfunction
/league town place bca_default_mid_12_-7 kanto_badges
```

The command uses the town's atlas site as its scope. A selected archetype cannot
be assigned to any second town on that continent, and a placed town gym is saved
with its exact position so it cannot be placed twice accidentally. To inspect or
reserve the allocation before choosing a plot, use:

```mcfunction
/league town gym bca_default_mid_12_-7 kanto_badges
```

`/league town register <id>` remains available only for a player-built or
third-party settlement that does not expose a detectable structure start.

`structure_tag` is currently a registered structure ID despite the historical
field name. If its provider is absent or a plot is unsuitable, placement fails
without recording a placed gym; the allocation remains reserved for that town
and can be retried after fixing the problem.

## Content data

Content belongs in a normal server datapack. `species_pools` are referenced by
the exact resource ID already placed in Region Engine profiles.

`data/region_engine/region_profiles/kanto.json`:

```json
{
  "cobblemon": {
    "allow_species_tags": ["league:kanto_native"],
    "deny_species_tags": ["league:johto_native"]
  }
}
```

`data/league/league_orchestrator/species_pools/kanto_native.json`:

```json
{
  "species": [
    "cobblemon:bulbasaur",
    "cobblemon:charmander",
    "cobblemon:squirtle"
  ]
}
```

An allow list applies only after its corresponding pool has loaded. Add all
forms/species wanted by the pack; the filter compares Cobblemon species IDs and
does not infer a real-world region from a biome.

Gym archetypes are intentionally separate from regional Pokémon pools:

`data/league/league_orchestrator/gym_archetypes/verdant.json`:

```json
{
  "pool": "starter_badges",
  "region_profiles": ["region_engine:kanto", "region_engine:johto"],
  "weight": 2,
  "structure_tag": "league:verdant_gym",
  "trainer": "rctmod:leader_example",
  "badge": "league:verdant"
}
```

If `starter_badges` has five entries and five Atlas sites claim that pool, each
entry is allocated once. Use a single pool to emulate unique local leaders, or
separate pools such as `fire_badges`, `water_badges`, and `grass_badges` to
randomize independently by Pokémon type. The resolved allocation is stored in
world saved data and is never normally reshuffled.

## Kanto Classic built-in pack

The mod includes the normal, opt-in **League Orchestrator — Kanto Classic
regional profile and species policy** data pack. Enable it above Region Engine
Atlas Test when creating a new world. It replaces only the `region_engine:kanto`
profile, leaving every other continent available for a different regional pack.

For the current built-in packs, data-pack priority from bottom to top is:

1. Any Region Engine biome-compatibility packs for installed biome mods.
2. **Region Engine — Atlas Test**.
3. **League Orchestrator — Kanto Classic regional profile and species policy**.
4. **League Orchestrator — BCA Kanto Towns** (when Cobblemon Additions is installed).
5. **League Orchestrator — Content Compatibility** (when BCA and/or Radical Trainers are installed).

Kanto Classic must be above Atlas Test because it overrides the base Kanto
profile. BCA Kanto Towns must be above Kanto Classic because it overrides BCA's
`minecraft:villages` structure set. Content Compatibility may sit above all of
them because it touches only BCA and Radical Trainers resources. Select the
packs before creating the world and do not alter the selected worldgen packs
after chunks have generated.

### Optional BCA Kanto towns

The normal, opt-in **League Orchestrator — BCA Kanto Towns** pack is for packs
that include Cobblemon Additions. It replaces BCA's generic village mix with a
Kanto-focused mix that strongly favors mid-sized and large settlements, while
leaving a few small settlements for texture. It also defines a sparse
`league_orchestrator:wild_pokecenter` jigsaw structure that uses BCA's own
Pokécenter template at runtime. These are genuine structures, not map markers:
RoadWeaver may safely include the wild Pokécenter ID as an intermediate static
endpoint. Its structure set excludes the village set, keeping these centers in
the wild rather than on a town's doorstep.

### Optional content compatibility

The normal, opt-in **League Orchestrator — Content Compatibility** pack repairs
two optional-content references in the current test stack without adding a
dependency on either provider. It replaces BCA's missing CobbleNav fishing
item-group loot with a small vanilla fishing kit, and replaces Radical Trainers'
broken `common` parent table with a table that selects only the loot subtables
present in the installed Radical Trainers jar. Enable it above the provider
packs when those mods are installed. It does not fabricate Mega Showdown items
for a handful of Radical Trainer definitions; install Mega Showdown to enable
those optional held-item loadouts. Without it, Radical Trainers logs the
provider-side validation warnings and omits those unavailable held items.

Kanto Classic uses a temperate Tokyo/Kanto-inspired palette: plains, forests,
river valleys, beaches, wetlands, and modest windswept/stony mountains. It
deliberately excludes jungle, desert, badlands, taiga, frozen, and ice biomes.
Its profile terrain favors mixed lowlands and mountains while minimizing large
inland lakes.

The initial species policy contains the 146 non-mythical Generation-I Cobblemon
species. Articuno, Zapdos, Moltres, and Mewtwo are denied to normal wild spawns
and reserved for future structure encounters; Mew is not included in the normal
Kanto encounter policy. This is a regional eligibility layer, not a copied
FireRed/LeafGreen or HeartGold/SoulSilver encounter table: it prevents unrelated
species from leaking in, but does not invent a spawn entry where Cobblemon has
none. The next content pass will supply original habitat/route-tier spawn pools
and the separately curated HeartGold/SoulSilver Kanto extension set.

Kanto Classic also supplies eight `kanto_badges` gym archetypes—rock, water,
electric, grass, poison, psychic, fire, and ground—each pointing to the matching
Rad Gyms entrance structure. They are not naturally generated by League
Orchestrator: use the discovered BCA town workflow to place one at a chosen town
plot. This is a data reference to Rad Gyms' public structure IDs,
not copied gym content.

## Development

This inner repository is intentionally separate from Region Engine and Koyo.
The local build uses development-only compile contracts for the adjacent Region
Engine build and a user-supplied Cobblemon 1.7.3 jar; neither is embedded in
the output.

```powershell
.\gradlew.bat clean build --no-daemon -Pcobblemon_dev_jar="C:\path\to\Cobblemon-fabric-1.7.3+1.21.1.jar"
```
