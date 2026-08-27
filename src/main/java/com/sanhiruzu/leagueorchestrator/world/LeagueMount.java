package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import com.sanhiruzu.regionengine.world.atlas.AtlasScope;
import com.sanhiruzu.regionengine.world.atlas.AtlasScopeData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Resolves the dimension League content is mounted in.
 *
 * <p>League generates into exactly one dimension per server. A pack played
 * solo mounts in the main world; a server may instead mount League in its own
 * dimension and keep a separate persistent overworld as its social hub. Every
 * League subsystem that needs "the League world" asks here rather than
 * assuming {@code server.overworld()}, so that choice stays in one place.
 *
 * <p>The mount is decided by the world, not by configuration: a world created
 * with the League Dimension world type contains {@link #LEAGUE_DIMENSION} and
 * mounts there; any other world mounts in the overworld. Because the dimension
 * set is fixed when a world is created, this cannot drift, and there is no
 * config value that can disagree with the world it is running against.
 *
 * <p>{@link WorldLeaguePlan} is per-level
 * {@link net.minecraft.world.level.saveddata.SavedData}, so a plan follows its
 * mounted dimension without migration.
 */
public final class LeagueMount {
    /** Dimension supplied by the {@code league_orchestrator:league_dimension} world preset. */
    public static final ResourceKey<Level> LEAGUE_DIMENSION = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(LeagueOrchestrator.MOD_ID, "league"));

    /**
     * Structure-generator states belonging to the mounted level. Structure
     * placement runs deep in worldgen with no idea which dimension it is
     * generating, so each level registers its own state as it loads, when the
     * dimension is known for free.
     */
    private static final Set<ChunkGeneratorStructureState> MOUNTED_STATES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static volatile ResourceKey<Level> resolved = Level.OVERWORLD;

    private LeagueMount() {
    }

    /**
     * Settles which dimension League uses for this run. Called once at server
     * start, before any level is classified.
     */
    public static void resolve(MinecraftServer server) {
        MOUNTED_STATES.clear();
        // SERVER_STARTING runs before levels are created, so ask the dimension
        // registry rather than the level map. The registry comes from world
        // data and is already populated here.
        boolean hasLeagueDimension = server.registryAccess()
                .registryOrThrow(Registries.LEVEL_STEM)
                .containsKey(ResourceKey.create(Registries.LEVEL_STEM, LEAGUE_DIMENSION.location()));
        resolved = hasLeagueDimension ? LEAGUE_DIMENSION : Level.OVERWORLD;
        LeagueOrchestrator.LOGGER.info("League content is mounted in {}", resolved.location());
        // Region Engine records Atlas scope when a new world first runs, which
        // happens after this. Telling it now means the world type is the only
        // decision an operator makes: picking the League Dimension world type
        // shapes the League dimension and leaves the hub overworld vanilla.
        AtlasScope.suggestDefaultScope(Set.of(resolved.location().toString()));
    }

    /** The level League generates and plans into. */
    public static ServerLevel level(MinecraftServer server) {
        ServerLevel level = server.getLevel(resolved);
        return level != null ? level : server.overworld();
    }

    /** True when this level is the mounted League dimension. */
    public static boolean isMounted(ServerLevel level) {
        return level.dimension().equals(resolved);
    }

    /** True when this dimension id names the mounted League dimension. */
    public static boolean isMounted(MinecraftServer server, ResourceLocation dimension) {
        return resolved.location().equals(dimension);
    }

    /** Records a level's structure state as it loads, so worldgen can be scoped. */
    public static void classify(ServerLevel level) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        if (isMounted(level)) {
            MOUNTED_STATES.add(state);
        } else {
            // A /reload or datapack swap rebuilds generators; a stale instance
            // must not stay marked as the mount.
            MOUNTED_STATES.remove(state);
        }
    }

    /**
     * Whether structure placement running against this state is generating the
     * mounted League dimension. False for every other dimension, which is what
     * keeps League towns out of a hub overworld.
     */
    public static boolean isMountedState(ChunkGeneratorStructureState state) {
        return MOUNTED_STATES.contains(state);
    }

    /**
     * Reports when the mounted dimension has no Atlas geography. League towns
     * are placed against Region Engine's continent classification, so a mount
     * outside the Atlas scope would predict towns on terrain that has no
     * continents. Atlas scope is fixed at world creation and cannot be
     * repaired here, so this reports rather than tries to correct it.
     */
    public static void verifyAtlasScope(MinecraftServer server) {
        if (!LEAGUE_DIMENSION.equals(resolved)) {
            return;
        }
        AtlasScopeData scope = AtlasScopeData.get(server.overworld());
        if (!scope.covers(resolved.location().toString())) {
            LeagueOrchestrator.LOGGER.error(
                    "League is mounted in {} but this world's Atlas scope is {}. Towns are placed "
                            + "against Atlas continents, so this world will not generate a coherent "
                            + "campaign. Atlas scope is fixed at world creation: use a new world with "
                            + "atlasDimensions covering {}.",
                    resolved.location(), scope.dimensions(), resolved.location());
        }
    }

    public static void clear() {
        MOUNTED_STATES.clear();
        resolved = Level.OVERWORLD;
    }
}
