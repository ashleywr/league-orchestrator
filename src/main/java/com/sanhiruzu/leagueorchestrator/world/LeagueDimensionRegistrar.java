package com.sanhiruzu.leagueorchestrator.world;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.fabricmc.fabric.api.event.registry.DynamicRegistrySetupCallback;
import net.fabricmc.fabric.api.event.registry.DynamicRegistryView;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds each assigned League dimension slot as a pure-vanilla
 * {@code minecraft:noise} generator, reusing vanilla's overworld noise shape
 * but with a region-specific biome placement read from a bundled region
 * profile (see {@code region_profiles/*.json} - plain bundled resources, not
 * datapack content, so they never auto-register as dimensions of their own).
 *
 * <p>Registers directly into the {@link Registries#LEVEL_STEM} registry from
 * {@link DynamicRegistrySetupCallback}. That event fires once per registry
 * <em>layer</em> of Fabric's combined registry manager, and
 * {@code Registries#LEVEL_STEM} decodes in a later, separate layer
 * ({@code RegistryLayer.DIMENSIONS}) from the biome/dimension-type/noise-settings
 * registries it needs to reference ({@code RegistryLayer.WORLDGEN}) - those
 * are never present together in a single firing. So each firing caches
 * whichever lookups it can see, and the actual registration happens once
 * {@code LEVEL_STEM} itself becomes available, using the lookups cached from
 * the earlier firing. Cross-registry references are resolved through
 * {@link WritableRegistry#createRegistrationLookup()}, the same
 * forward-reference mechanism vanilla's own bootstrap registration uses.
 */
public final class LeagueDimensionRegistrar {
    /** Region profile ids with a bundled {@code region_profiles/<id>.json} template. */
    public static final java.util.Set<String> KNOWN_REGIONS = java.util.Set.of("kanto", "alola");

    private static final Gson GSON = new Gson();

    private static volatile HolderGetter<Biome> cachedBiomeLookup;
    private static volatile HolderGetter<DimensionType> cachedDimensionTypeLookup;
    private static volatile HolderGetter<NoiseGeneratorSettings> cachedNoiseSettingsLookup;

    private LeagueDimensionRegistrar() {
    }

    public static void register() {
        DynamicRegistrySetupCallback.EVENT.register(LeagueDimensionRegistrar::onRegistrySetup);
    }

    private static void onRegistrySetup(DynamicRegistryView registryView) {
        registryView.getOptional(Registries.BIOME)
                .filter(registry -> registry instanceof WritableRegistry<Biome>)
                .ifPresent(registry -> cachedBiomeLookup = ((WritableRegistry<Biome>) registry).createRegistrationLookup());
        registryView.getOptional(Registries.DIMENSION_TYPE)
                .filter(registry -> registry instanceof WritableRegistry<DimensionType>)
                .ifPresent(registry -> cachedDimensionTypeLookup = ((WritableRegistry<DimensionType>) registry).createRegistrationLookup());
        registryView.getOptional(Registries.NOISE_SETTINGS)
                .filter(registry -> registry instanceof WritableRegistry<NoiseGeneratorSettings>)
                .ifPresent(registry -> cachedNoiseSettingsLookup = ((WritableRegistry<NoiseGeneratorSettings>) registry).createRegistrationLookup());

        Registry<LevelStem> levelStems = registryView.getOptional(Registries.LEVEL_STEM).orElse(null);
        if (levelStems == null) return;
        if (!(levelStems instanceof WritableRegistry<LevelStem> writableLevelStems)) {
            LeagueOrchestrator.LOGGER.warn("League dimension slots were not registered: LEVEL_STEM registry is not writable.");
            return;
        }
        HolderGetter<Biome> biomeLookup = cachedBiomeLookup;
        HolderGetter<DimensionType> dimensionTypeLookup = cachedDimensionTypeLookup;
        HolderGetter<NoiseGeneratorSettings> noiseSettingsLookup = cachedNoiseSettingsLookup;
        if (biomeLookup == null || dimensionTypeLookup == null || noiseSettingsLookup == null) {
            LeagueOrchestrator.LOGGER.error(
                    "League dimension slots were not registered: LEVEL_STEM became available before biome ({}), "
                            + "dimension-type ({}), or noise-settings ({}) registration lookups were cached. "
                            + "This means the registry layer ordering changed from what LeagueDimensionRegistrar assumes.",
                    biomeLookup != null, dimensionTypeLookup != null, noiseSettingsLookup != null);
            return;
        }

        Holder<DimensionType> overworldType = dimensionTypeLookup.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        Holder<NoiseGeneratorSettings> overworldNoise = noiseSettingsLookup.getOrThrow(NoiseGeneratorSettings.OVERWORLD);

        boolean any = false;
        for (int slot = 1; slot <= LeagueMount.SLOT_COUNT; slot++) {
            String region = LeagueSlotAssignments.get().regionFor(slot);
            if (region == null || region.isBlank()) continue;
            any = true;
            try {
                LevelStem stem = buildStem(region, biomeLookup, overworldType, overworldNoise);
                ResourceKey<LevelStem> key = ResourceKey.create(Registries.LEVEL_STEM, LeagueMount.slotDimension(slot).location());
                writableLevelStems.register(key, stem, RegistrationInfo.BUILT_IN);
                LeagueOrchestrator.LOGGER.info("Registered League dimension slot {} as region '{}'.", slot, region);
            } catch (IOException | RuntimeException exception) {
                LeagueOrchestrator.LOGGER.error("Could not register League dimension slot {} (region '{}')", slot, region, exception);
            }
        }
        if (!any) {
            LeagueOrchestrator.LOGGER.info("No League dimension slots are assigned; every slot stays unregistered.");
        }
    }

    private static LevelStem buildStem(String region, HolderGetter<Biome> biomeLookup,
                                        Holder<DimensionType> overworldType,
                                        Holder<NoiseGeneratorSettings> overworldNoise) throws IOException {
        JsonObject biomeSourceJson = readBiomeSource(region);
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> entries = new ArrayList<>();
        for (var element : biomeSourceJson.getAsJsonArray("biomes")) {
            JsonObject entry = element.getAsJsonObject();
            ResourceLocation biomeId = ResourceLocation.parse(entry.get("biome").getAsString());
            Holder<Biome> biomeHolder = biomeLookup.getOrThrow(ResourceKey.create(Registries.BIOME, biomeId));
            JsonObject params = entry.getAsJsonObject("parameters");
            Climate.ParameterPoint point = Climate.parameters(
                    span(params, "temperature"), span(params, "humidity"), span(params, "continentalness"),
                    span(params, "erosion"), span(params, "depth"), span(params, "weirdness"),
                    params.get("offset").getAsFloat());
            entries.add(Pair.of(point, biomeHolder));
        }
        MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(entries));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomeSource, overworldNoise);
        return new LevelStem(overworldType, generator);
    }

    private static Climate.Parameter span(JsonObject params, String key) {
        JsonArray array = params.getAsJsonArray(key);
        return Climate.Parameter.span(array.get(0).getAsFloat(), array.get(1).getAsFloat());
    }

    private static JsonObject readBiomeSource(String region) throws IOException {
        String path = "/region_profiles/" + region + ".json";
        try (InputStream stream = LeagueDimensionRegistrar.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("No region profile bundled at " + path);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                return root.getAsJsonObject("generator").getAsJsonObject("biome_source");
            }
        }
    }
}
