# Mountain caves and underground landmarks

Large caves are not a surface-biome problem. Keep the surface taxonomy
contiguous, then add underground geography as a separate, elevation-aware
layer.

## Recommended progression

1. **Keep vanilla/Terra carving and ores enabled.** This is the baseline for
   ordinary caves, aquifers, ore placement, and compatibility.
2. **Add a mountain-only cave biome layer.** It may change cave palette,
   deposits, flora, and structures beneath a landmark mountain, but must run
   after surface biome selection and within a bounded Y range. It must never
   replace the mountain's surface biome.
3. **Add cavern volume separately.** A cave biome alone only changes the
   identity of blocks once a cave exists. To create a genuinely large cavern,
   add a bounded 3D density subtraction or Terra terrain-expression sampler
   inside the mountain's interior. Use a low-frequency warped field for one
   or two chambers and a higher-frequency connector field; clamp it below the
   summit and above the deep bedrock/ore layers.
4. **Decorate only after volume works.** Stalactites, underground rivers,
   ruins, fossils, and rare ores should be configured as features for the
   mountain-cave biome, not used as a substitute for cave volume.

## Kanto Terra candidate contract

For Kanto, the hydrology add-on already owns the seed-stable inland landmark
mountain. A future `MOUNTAIN_CAVERN` sampler/extrusion should use that same
landmark field, so chambers cannot appear under unrelated plains or coast.
The target envelope is roughly the mountain interior below the ice cap and
above normal deep-cave layers. It should be a best-effort one-to-two cavern
system per continent, not a guaranteed repeated coordinate stamp.

Validation must use a fresh isolated world and compare against the accepted
terrain baseline:

- overview, relief, and physical-water screenshots;
- an in-world mountain cross-section/cavern screenshot;
- macro-biome contiguity audit unchanged at surface level;
- cold FULL chunks at the mountain core, foothills, and a non-mountain control;
- cave-air percentage and ore count in all three chunks; and
- initial world and cold-chunk timing within the accepted budget.

Do not use a broad `is_cave` or `is_mountain` spawn tag as the cavern locator.
Those tags are for Cobblemon habitat selection after terrain generation. The
terrain locator must be the same seed-stable geological field that selected
the mountain.
