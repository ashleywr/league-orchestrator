package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the League world preset from Terra's live Kanto generator.
 *
 * <p>Terra config packs are not available while ordinary datapack world
 * presets decode.  Terra creates {@code terra:kanto/kanto} later during its
 * registry lifecycle, so this bridge runs immediately afterwards and reuses
 * that already-resolved Kanto stem.  The normal preset remains the hub
 * overworld; only {@code league_orchestrator:league} receives Terra terrain.</p>
 */
public final class TerraLeaguePreset {
    public static final ResourceKey<WorldPreset> KANTO_DIMENSION_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath(LeagueOrchestrator.MOD_ID, "kanto_dimension")
    );
    private static final ResourceKey<WorldPreset> TERRA_KANTO_PRESET = ResourceKey.create(
            Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath("terra", "kanto/kanto")
    );
    private static final ResourceKey<LevelStem> LEAGUE_STEM = ResourceKey.create(
            Registries.LEVEL_STEM,
            LeagueMount.LEAGUE_DIMENSION.location()
    );

    private TerraLeaguePreset() {
    }

    public static void register(WritableRegistry<WorldPreset> presets) {
        if (presets.containsKey(KANTO_DIMENSION_PRESET)) return;

        WorldPreset normal = presets.get(WorldPresets.NORMAL);
        WorldPreset terraKanto = presets.get(TERRA_KANTO_PRESET);
        if (normal == null || terraKanto == null) {
            LeagueOrchestrator.LOGGER.warn("Kanto League world preset was not registered: normal={}, terraKanto={}",
                    normal != null, terraKanto != null);
            return;
        }

        LevelStem kantoStem = terraKanto.createWorldDimensions().dimensions().get(LevelStem.OVERWORLD);
        if (kantoStem == null) {
            LeagueOrchestrator.LOGGER.warn("Kanto League world preset was not registered: Terra Kanto has no overworld stem.");
            return;
        }

        Map<ResourceKey<LevelStem>, LevelStem> dimensions = new LinkedHashMap<>(
                normal.createWorldDimensions().dimensions()
        );
        dimensions.put(LEAGUE_STEM, kantoStem);
        Registry.register(presets, KANTO_DIMENSION_PRESET, new WorldPreset(dimensions));
        LeagueOrchestrator.LOGGER.info("Registered Kanto League preset: vanilla hub overworld + {} Terra dimension.",
                LEAGUE_STEM.location());
    }
}
