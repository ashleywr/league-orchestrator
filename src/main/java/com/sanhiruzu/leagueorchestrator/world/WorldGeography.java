package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The small geography contract League needs from a world generator.
 *
 * <p>Region Engine remains the preferred provider when installed.  When it is
 * absent, an OTG Kanto preset may expose its seed-only continent API. League
 * then plans the one true mainland rather than inventing a grid of continents.
 * Terra is also supported as a runtime generator: its Kanto pack remains the
 * geographic authority, so League never derives a second land mask from Terra
 * internals.
 */
public final class WorldGeography {
    public static final ResourceLocation KANTO_PROFILE = ResourceLocation.fromNamespaceAndPath("region_engine", "kanto");
    public static final ResourceLocation FRONTIER_PROFILE = ResourceLocation.fromNamespaceAndPath("league_orchestrator", "frontier");
    private static final int FALLBACK_CELL_SIZE = 4_096;
    private static final int FALLBACK_ENVELOPE = 1_920;
    private static final int OTG_MAINLAND_SIZE = 16_384;
    private static final int OTG_MAINLAND_ENVELOPE = 8_192;
    /** Kanto Terra v1's finite image atlas is 1,256 pixels at 16 blocks each. */
    private static final int TERRA_KANTO_ATLAS_SIZE = 20_096;
    private static final int TERRA_KANTO_ATLAS_ENVELOPE = TERRA_KANTO_ATLAS_SIZE / 2;
    private static final String OTG_KANTO_PRESET = "KantoPreset";
    private static final String TERRA_MOD_ID = "terra";
    private static final String TERRA_PACKAGE_PREFIX = "com.dfsek.terra.";
    private static volatile boolean regionEngineFailed;
    private static volatile boolean otgContinentApiFailed;

    private WorldGeography() {}

    public enum Kind { CONTINENT, OCEAN }

    public record Site(Kind kind, int cellX, int cellZ) {}

    public record Center(double x, double z) {}

    public static boolean usesRegionEngine() {
        return !regionEngineFailed && FabricLoader.getInstance().isModLoaded("region_engine");
    }

    /** True when Terra is present; use {@link #isTerraWorld(ServerLevel)} for world-specific decisions. */
    public static boolean usesTerra() {
        return !usesRegionEngine() && FabricLoader.getInstance().isModLoaded(TERRA_MOD_ID);
    }

    public static Site siteAt(long seed, int blockX, int blockZ) {
        if (usesRegionEngine()) {
            try {
                Object atlas = regionPolicy().getMethod("atlasRegionAt", long.class, int.class, int.class)
                        .invoke(null, seed, blockX, blockZ);
                Object rawKind = atlas.getClass().getMethod("kind").invoke(atlas);
                int cellX = (Integer) atlas.getClass().getMethod("cellX").invoke(atlas);
                int cellZ = (Integer) atlas.getClass().getMethod("cellZ").invoke(atlas);
                return new Site("OCEAN".equals(String.valueOf(rawKind)) ? Kind.OCEAN : Kind.CONTINENT, cellX, cellZ);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                disableRegionEngineProvider(exception);
            }
        }
        if (usesOtgContinentApi()) {
            return otgMainlandSite(seed, blockX, blockZ);
        }
        return new Site(Kind.CONTINENT, Math.floorDiv(blockX, FALLBACK_CELL_SIZE), Math.floorDiv(blockZ, FALLBACK_CELL_SIZE));
    }

    /**
     * World-aware geographic lookup. Terra's Kanto pack is one finite site;
     * this overload avoids changing a vanilla world just because Terra happens
     * to be installed in the same client.
     */
    public static Site siteAt(ServerLevel level, int blockX, int blockZ) {
        if (isTerraWorld(level)) {
            return Math.abs((long) blockX) < TERRA_KANTO_ATLAS_ENVELOPE
                    && Math.abs((long) blockZ) < TERRA_KANTO_ATLAS_ENVELOPE
                    ? new Site(Kind.CONTINENT, 0, 0)
                    : new Site(Kind.OCEAN, 0, 0);
        }
        return siteAt(level.getSeed(), blockX, blockZ);
    }

    public static ResourceLocation profileAt(ServerLevel level, double blockX, double blockZ) {
        if (usesRegionEngine()) {
            try {
                Object profile = regionPolicy().getMethod("profileAt", ServerLevel.class, double.class, double.class)
                        .invoke(null, level, blockX, blockZ);
                return (ResourceLocation) profile.getClass().getMethod("id").invoke(profile);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                disableRegionEngineProvider(exception);
            }
        }
        if (isTerraWorld(level) || usesOtgContinentApi()) return KANTO_PROFILE;
        return FRONTIER_PROFILE;
    }

    public static ResourceLocation profileForSite(ServerLevel level, long seed, int cellX, int cellZ) {
        if (usesRegionEngine()) {
            try {
                Object profile = regionPolicy().getMethod("profileForSite", long.class, int.class, int.class)
                        .invoke(null, seed, cellX, cellZ);
                return (ResourceLocation) profile.getClass().getMethod("id").invoke(profile);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                disableRegionEngineProvider(exception);
            }
        }
        if (isTerraWorld(level) || usesOtgContinentApi()) return KANTO_PROFILE;
        return FRONTIER_PROFILE;
    }

    public static int cellSize() {
        if (usesOtgContinentApi()) return OTG_MAINLAND_SIZE;
        return regionConfigInt("cellSize", FALLBACK_CELL_SIZE);
    }

    public static int cellSize(ServerLevel level) {
        return isTerraWorld(level) ? TERRA_KANTO_ATLAS_SIZE : cellSize();
    }

    public static int envelope() {
        if (usesOtgContinentApi()) return OTG_MAINLAND_ENVELOPE;
        return (int) Math.ceil(regionConfigNumber("maxRadius", FALLBACK_ENVELOPE)
                + regionConfigNumber("maxCoastProtrusion", 0.0));
    }

    public static int envelope(ServerLevel level) {
        return isTerraWorld(level) ? TERRA_KANTO_ATLAS_ENVELOPE : envelope();
    }

    public static Center siteCenter(long seed, int cellX, int cellZ) {
        if (usesRegionEngine()) {
            try {
                Object center = Class.forName("com.sanhiruzu.regionengine.world.atlas.AtlasMath")
                        .getMethod("siteCenter", long.class, int.class, int.class).invoke(null, seed, cellX, cellZ);
                return new Center(((Number) center.getClass().getMethod("x").invoke(center)).doubleValue(),
                        ((Number) center.getClass().getMethod("z").invoke(center)).doubleValue());
            } catch (ReflectiveOperationException | RuntimeException exception) {
                disableRegionEngineProvider(exception);
            }
        }
        if (usesOtgContinentApi()) return new Center(0.0, 0.0);
        return new Center((double) cellX * FALLBACK_CELL_SIZE, (double) cellZ * FALLBACK_CELL_SIZE);
    }

    public static Center siteCenter(ServerLevel level, int cellX, int cellZ) {
        return isTerraWorld(level) ? new Center(0.0, 0.0) : siteCenter(level.getSeed(), cellX, cellZ);
    }

    /** Region Place is an optional Region Engine convenience index, not campaign state. */
    public static void publishPlace(ServerLevel level, TownAnchor town, boolean realized, boolean fallback) {
        // The League saved-data ledger is authoritative.  Do not load Region
        // Engine-only classes here: OTG worlds must be able to start without it.
    }

    public static String chooseName(long seed, String scope, java.util.List<String> candidates, String fallback) {
        if (candidates.isEmpty()) return fallback;
        return candidates.get(Math.floorMod((int) (seed ^ scope.hashCode()), candidates.size()));
    }

    private static Class<?> regionPolicy() throws ClassNotFoundException {
        return Class.forName("com.sanhiruzu.regionengine.policy.RegionPolicyService");
    }

    private static boolean usesOtgContinentApi() {
        return !otgContinentApiFailed && !usesRegionEngine() && !usesTerra()
                && FabricLoader.getInstance().isModLoaded("otg");
    }

    /**
     * Terra's public contract is the active generator/biome source, not a
     * global mod-presence flag. This prevents a Terra installation from
     * changing ordinary vanilla worlds in the same instance.
     */
    public static boolean isTerraWorld(ServerLevel level) {
        if (!usesTerra()) return false;
        try {
            Object generator = level.getChunkSource().getGenerator();
            if (generator.getClass().getName().startsWith(TERRA_PACKAGE_PREFIX)) return true;
            Object biomeSource = generator.getClass().getMethod("getBiomeSource").invoke(generator);
            return biomeSource.getClass().getName().startsWith(TERRA_PACKAGE_PREFIX);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LeagueOrchestrator.LOGGER.debug("Unable to inspect the active generator for Terra compatibility.", exception);
            return false;
        }
    }

    private static Site otgMainlandSite(long seed, int blockX, int blockZ) {
        try {
            Object sample = Class.forName("com.pg85.otg.api.OTGContinentApi")
                    .getMethod("query", String.class, long.class, int.class, int.class)
                    .invoke(null, OTG_KANTO_PRESET, seed, blockX, blockZ);
            Object kind = sample.getClass().getMethod("kind").invoke(sample);
            return "MAINLAND".equals(String.valueOf(kind))
                    ? new Site(Kind.CONTINENT, 0, 0)
                    : new Site(Kind.OCEAN, 0, 0);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!otgContinentApiFailed) {
                otgContinentApiFailed = true;
                LeagueOrchestrator.LOGGER.warn("OTG continent API is unavailable; League will not place OTG Kanto towns until it is restored.", exception);
            }
            return new Site(Kind.OCEAN, 0, 0);
        }
    }

    private static int regionConfigInt(String field, int fallback) {
        return (int) regionConfigNumber(field, fallback);
    }

    private static double regionConfigNumber(String fieldName, double fallback) {
        if (!usesRegionEngine()) return fallback;
        try {
            Class<?> configClass = Class.forName("com.sanhiruzu.regionengine.config.AtlasConfig");
            Object config = configClass.getMethod("get").invoke(null);
            Field field = configClass.getField(fieldName);
            return ((Number) field.get(config)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            disableRegionEngineProvider(exception);
            return fallback;
        }
    }

    private static void disableRegionEngineProvider(Exception exception) {
        if (!regionEngineFailed) {
            regionEngineFailed = true;
            LeagueOrchestrator.LOGGER.warn("Region Engine geography is unavailable; League is using its OTG-compatible fallback.", exception);
        }
    }
}
