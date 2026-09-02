package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the dimensions League content is mounted in.
 *
 * <p>League generates into a config-driven pool of up to {@link #SLOT_COUNT}
 * dimension slots ({@code league_orchestrator:league_1}..{@code league_N}),
 * each independently assignable to a region profile by
 * {@link LeagueSlotAssignments} and a server command. Zero or more slots may
 * be mounted at once. A pack played solo, or a world with no slots assigned
 * at all, falls back to mounting League in the main world instead - this is
 * the legacy "Terra replaces the overworld" layout, kept working for older
 * setups. Every League subsystem that needs "a League world" asks here
 * rather than assuming {@code server.overworld()}, so that choice stays in
 * one place.
 *
 * <p>The mounted set is decided by which slot dimensions actually exist in
 * the world's {@link Registries#LEVEL_STEM} registry at server start - see
 * {@link LeagueSlotAssignments} for how a slot's assigned region ends up
 * registered there. Because that registry is rebuilt from currently-loaded
 * data every boot, reassigning a slot and restarting is enough; no world
 * recreation is required.
 *
 * <p>{@link WorldLeaguePlan} is per-level
 * {@link net.minecraft.world.level.saveddata.SavedData}, so a plan follows its
 * mounted dimension without migration, and multiple mounted slots get
 * independent plans automatically.
 */
public final class LeagueMount {
    /** Maximum number of concurrently assignable league dimension slots. */
    public static final int SLOT_COUNT = 5;

    /** The {@code league_orchestrator:league_1}..{@code league_N} slot dimension keys, in order. */
    private static final List<ResourceKey<Level>> SLOT_DIMENSIONS = buildSlotDimensions();

    private static List<ResourceKey<Level>> buildSlotDimensions() {
        List<ResourceKey<Level>> slots = new ArrayList<>(SLOT_COUNT);
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            slots.add(ResourceKey.create(
                    Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(LeagueOrchestrator.MOD_ID, "league_" + slot)));
        }
        return List.copyOf(slots);
    }

    /** The slot dimension for a 1-based slot number, e.g. {@code slotDimension(1)} is {@code league_1}. */
    public static ResourceKey<Level> slotDimension(int slot) {
        return SLOT_DIMENSIONS.get(slot - 1);
    }

    /**
     * Structure-generator states belonging to a mounted level. Structure
     * placement runs deep in worldgen with no idea which dimension it is
     * generating, so each level registers its own state as it loads, when the
     * dimension is known for free.
     */
    private static final Set<ChunkGeneratorStructureState> MOUNTED_STATES =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /** Generator states for mounted levels that actually use Terra. */
    private static final Set<ChunkGeneratorStructureState> TERRA_MOUNTED_STATES =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /** Falls back to the overworld when no slot is mounted - the legacy layout. */
    private static volatile Set<ResourceKey<Level>> mountedSlots = Set.of(Level.OVERWORLD);

    private LeagueMount() {
    }

    /**
     * Settles which dimensions League uses for this run. Called once at
     * server start, before any level is classified.
     */
    public static void resolve(MinecraftServer server) {
        MOUNTED_STATES.clear();
        TERRA_MOUNTED_STATES.clear();
        // SERVER_STARTING runs before levels are created, so ask the dimension
        // registry rather than the level map. The registry comes from world
        // data and is already populated here.
        var stems = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        Set<ResourceKey<Level>> mounted = new LinkedHashSet<>();
        for (ResourceKey<Level> slot : SLOT_DIMENSIONS) {
            if (stems.containsKey(ResourceKey.create(Registries.LEVEL_STEM, slot.location()))) {
                mounted.add(slot);
            }
        }
        mountedSlots = mounted.isEmpty() ? Set.of(Level.OVERWORLD) : Set.copyOf(mounted);
        LeagueOrchestrator.LOGGER.info("League content is mounted in {}",
                mountedSlots.stream().map(key -> key.location().toString()).collect(Collectors.joining(", ")));
    }

    /** A level League generates and plans into. Prefer {@link #mountedLevels(MinecraftServer)} when acting on every active league. */
    public static ServerLevel level(MinecraftServer server) {
        for (ResourceKey<Level> slot : mountedSlots) {
            ServerLevel level = server.getLevel(slot);
            if (level != null) return level;
        }
        return server.overworld();
    }

    /** Every currently mounted League level. */
    public static List<ServerLevel> mountedLevels(MinecraftServer server) {
        List<ServerLevel> levels = new ArrayList<>(mountedSlots.size());
        for (ResourceKey<Level> slot : mountedSlots) {
            ServerLevel level = server.getLevel(slot);
            if (level != null) levels.add(level);
        }
        return levels;
    }

    /** True when this level is one of the mounted League dimensions. */
    public static boolean isMounted(ServerLevel level) {
        return mountedSlots.contains(level.dimension());
    }

    /** True when this dimension id names a mounted League dimension. */
    public static boolean isMounted(MinecraftServer server, ResourceLocation dimension) {
        for (ResourceKey<Level> slot : mountedSlots) {
            if (slot.location().equals(dimension)) return true;
        }
        return false;
    }

    /** Records a level's structure state as it loads, so worldgen can be scoped. */
    public static void classify(ServerLevel level) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        if (isMounted(level)) {
            MOUNTED_STATES.add(state);
            if (WorldGeography.isTerraWorld(level)) TERRA_MOUNTED_STATES.add(state);
        }
        // Do not remove a state when a non-mounted level loads.  Custom
        // generators may reuse one structure-state object between dimensions;
        // removing it here makes the overworld's deterministic town placement
        // appear invalid to an early RoadWeaver prediction pass.  The set is
        // cleared at server start/stop, and a reload contributes a new state.
    }

    /**
     * Whether structure placement running against this state is generating a
     * mounted League dimension. False for every other dimension, which is what
     * keeps League towns out of a hub overworld.
     */
    public static boolean isMountedState(ChunkGeneratorStructureState state) {
        return MOUNTED_STATES.contains(state);
    }

    /** True only for a structure state belonging to an actively mounted Terra world. */
    public static boolean isTerraMountedState(ChunkGeneratorStructureState state) {
        return TERRA_MOUNTED_STATES.contains(state);
    }

    /** Supports optional APIs that do not expose a structure state but run after level classification. */
    public static boolean hasTerraMountedState() {
        return !TERRA_MOUNTED_STATES.isEmpty();
    }

    /**
     * True only for the legacy layout where Terra Kanto is the overworld.
     *
     * <p>Terra can wrap a structure state while RoadWeaver is producing a
     * road-triggered chunk, so that legacy layout needs a conservative
     * level-scoped fallback.  A world with dedicated League slots must not use
     * that fallback: its hub overworld keeps ordinary village placement.</p>
     */
    public static boolean hasTerraOverworldMountedState() {
        return mountedSlots.equals(Set.of(Level.OVERWORLD)) && hasTerraMountedState();
    }

    /** Reports which geography provider is backing this campaign. */
    public static void verifyAtlasScope(MinecraftServer server) {
        LeagueOrchestrator.LOGGER.info("League geography provider: {}.",
                WorldGeography.usesRegionEngine() ? "Region Engine Atlas"
                        : WorldGeography.usesTerra() ? "Terra-compatible generator bridge"
                        : "deterministic fallback");
    }

    public static void clear() {
        MOUNTED_STATES.clear();
        TERRA_MOUNTED_STATES.clear();
        LeagueTownStructurePlacement.clearTerraCandidates();
        mountedSlots = Set.of(Level.OVERWORLD);
    }
}
