package com.sanhiruzu.leagueorchestrator.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The pure placement authority for League towns. Instead of an opaque global
 * spacing, it produces a target number of seed-stable candidates inside each
 * geography-provider site. Minecraft, League, and RoadWeaver enumerate the
 * exact same slots without loading terrain.
 */
public final class LeagueTownStructurePlacement extends StructurePlacement {
    private static final int MAX_ATTEMPTS_PER_TOWN = 128;
    private static final int MAX_CACHE_ENTRIES = 4_096;
    private static final ConcurrentHashMap<SiteKey, List<ChunkPos>> CANDIDATE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<ChunkPos>> TERRA_CANDIDATE_CACHE = new ConcurrentHashMap<>();
    private static final MapCodec<SlotRange> SLOT_RANGE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(0, 63).optionalFieldOf("slot_start", 0).forGetter(SlotRange::start),
            Codec.intRange(1, 64).optionalFieldOf("slot_count", 64).forGetter(SlotRange::count)
    ).apply(instance, SlotRange::new));

    public static final MapCodec<LeagueTownStructurePlacement> CODEC = RecordCodecBuilder
            .<LeagueTownStructurePlacement>mapCodec(instance -> placementCodec(instance).and(instance.group(
                    Codec.intRange(1, 64).optionalFieldOf("towns_per_continent", 10)
                            .forGetter(LeagueTownStructurePlacement::townsPerContinent),
                    Codec.intRange(0, 512).optionalFieldOf("minimum_separation", 80)
                            .forGetter(LeagueTownStructurePlacement::minimumSeparation),
                    SLOT_RANGE_CODEC.forGetter(placement -> new SlotRange(placement.slotStart(), placement.slotCount()))
            )).apply(instance, (offset, reductionMethod, frequency, salt, exclusionZone, townsPerContinent,
                                minimumSeparation, slotRange) -> new LeagueTownStructurePlacement(
                    offset, reductionMethod, frequency, salt, exclusionZone, townsPerContinent,
                    minimumSeparation, slotRange.start(), slotRange.count())));

    private final int townsPerContinent;
    private final int minimumSeparation;
    private final int slotStart;
    private final int slotCount;

    public LeagueTownStructurePlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod,
                                        float frequency, int salt, Optional<ExclusionZone> exclusionZone,
                                        int townsPerContinent, int minimumSeparation, int slotStart, int slotCount) {
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone);
        this.townsPerContinent = townsPerContinent;
        this.minimumSeparation = minimumSeparation;
        this.slotStart = slotStart;
        this.slotCount = slotCount;
    }

    public int townsPerContinent() {
        return townsPerContinent;
    }

    /** Minimum town-to-town centre distance, in chunks. */
    public int minimumSeparation() {
        return minimumSeparation;
    }

    /** First deterministic continent slot owned by this structure set. */
    public int slotStart() {
        return slotStart;
    }

    /** Number of deterministic continent slots owned by this structure set. */
    public int slotCount() {
        return slotCount;
    }

    /** True when this candidate is eligible for the initial RoadWeaver graph. */
    public boolean isRoadEndpoint(ChunkGeneratorStructureState state, ChunkPos candidate) {
        if (!LeagueMount.isTerraMountedState(state)) return true;
        List<ChunkPos> all = TERRA_CANDIDATE_CACHE.getOrDefault(state.getLevelSeed(), List.of());
        int index = all.indexOf(candidate);
        if (index < 0) return false;
        int leagueCount = TerraTownSitePlanner.leagueTownCount(state.getLevelSeed());
        if (index < leagueCount) return true;
        return TerraTownSitePlanner.isIncidentalRoadEndpoint(state.getLevelSeed(), index - leagueCount);
    }

    /** Enumerates candidate chunks in the supplied inclusive chunk rectangle. */
    public List<ChunkPos> potentialChunks(long worldSeed, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (LeagueMount.hasTerraMountedState()) {
            return selectedTerraCandidates(worldSeed).stream()
                    .filter(candidate -> candidate.x >= minChunkX && candidate.x <= maxChunkX
                            && candidate.z >= minChunkZ && candidate.z <= maxChunkZ)
                    .toList();
        }
        return potentialChunks(null, worldSeed, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    /** State-aware form used by League's own prediction path. */
    public List<ChunkPos> potentialChunks(ChunkGeneratorStructureState state, long worldSeed,
                                          int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (state != null && LeagueMount.isTerraMountedState(state)) {
            return selectedTerraCandidates(worldSeed).stream()
                    .filter(candidate -> candidate.x >= minChunkX && candidate.x <= maxChunkX
                            && candidate.z >= minChunkZ && candidate.z <= maxChunkZ)
                    .toList();
        }
        int cellSize = WorldGeography.cellSize();
        // Cell centres live on block-space multiples of cellSize, whereas
        // queries use chunks. Enumerate one neighbouring cell on each side
        // rather than approximating the division in chunk space; this avoids
        // a boundary miss whenever cellSize is not divisible by 16.
        int minCellX = Math.floorDiv(minChunkX * 16, cellSize) - 1;
        int maxCellX = Math.floorDiv(maxChunkX * 16, cellSize) + 1;
        int minCellZ = Math.floorDiv(minChunkZ * 16, cellSize) - 1;
        int maxCellZ = Math.floorDiv(maxChunkZ * 16, cellSize) + 1;
        List<ChunkPos> result = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (ChunkPos candidate : selectedCandidatesForSite(worldSeed, cellX, cellZ)) {
                    if (candidate.x >= minChunkX && candidate.x <= maxChunkX
                            && candidate.z >= minChunkZ && candidate.z <= maxChunkZ) result.add(candidate);
                }
            }
        }
        result.sort(Comparator.comparingInt((ChunkPos chunk) -> chunk.x).thenComparingInt(chunk -> chunk.z));
        return List.copyOf(result);
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        // Structure sets are registry-global, so this placement is consulted
        // for every dimension that runs structure generation. Only the mounted
        // League dimension may grow League towns; a hub overworld must not.
        if (!LeagueMount.isMountedState(state)) {
            return false;
        }
        ChunkPos current = new ChunkPos(chunkX, chunkZ);
        if (LeagueMount.isTerraMountedState(state)) {
            return selectedTerraCandidates(state.getLevelSeed()).contains(current);
        }
        WorldGeography.Site region = WorldGeography.siteAt(state.getLevelSeed(), current.getMiddleBlockX(), current.getMiddleBlockZ());
        return region.kind() == WorldGeography.Kind.CONTINENT
                && selectedCandidatesForSite(state.getLevelSeed(), region.cellX(), region.cellZ())
                .contains(new ChunkPos(chunkX, chunkZ));
    }

    @Override
    public StructurePlacementType<?> type() {
        return LeagueWorldgen.PLANNED_TOWN;
    }

    private List<ChunkPos> candidatesForSite(long worldSeed, int cellX, int cellZ) {
        SiteKey key = new SiteKey(worldSeed, cellX, cellZ, salt(), townsPerContinent, minimumSeparation);
        if (CANDIDATE_CACHE.size() > MAX_CACHE_ENTRIES) CANDIDATE_CACHE.clear();
        return CANDIDATE_CACHE.computeIfAbsent(key, ignored -> computeCandidates(worldSeed, cellX, cellZ));
    }

    private List<ChunkPos> selectedCandidatesForSite(long worldSeed, int cellX, int cellZ) {
        List<ChunkPos> all = candidatesForSite(worldSeed, cellX, cellZ);
        int first = Math.min(slotStart, all.size());
        int end = Math.min(all.size(), first + slotCount);
        return all.subList(first, end);
    }

    private List<ChunkPos> computeCandidates(long worldSeed, int cellX, int cellZ) {
        WorldGeography.Center center = WorldGeography.siteCenter(worldSeed, cellX, cellZ);
        double envelope = WorldGeography.envelope();
        int minimumDistanceBlocks = minimumSeparation * 16;
        long seed = worldSeed ^ salt() ^ ((long) cellX * 0x9E3779B97F4A7C15L)
                ^ ((long) cellZ * 0xC2B2AE3D27D4EB4FL);
        RandomSource random = RandomSource.create(seed);
        List<ChunkPos> result = new ArrayList<>(townsPerContinent);
        for (int slot = 0; slot < townsPerContinent; slot++) {
            ChunkPos anchor = kantoAnchor(slot);
            if (anchor != null) {
                WorldGeography.Site region = WorldGeography.siteAt(worldSeed, anchor.getMiddleBlockX(), anchor.getMiddleBlockZ());
                if (region.kind() == WorldGeography.Kind.CONTINENT && isFarEnough(anchor, result, minimumDistanceBlocks)) {
                    result.add(anchor);
                    continue;
                }
            }
            // Random slots stay inland so the planned network reads as a
            // regional settlement pattern rather than a ring of coast specks.
            double slotEnvelope = envelope * 0.76;
            for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_TOWN; attempt++) {
                double angle = random.nextDouble() * Math.TAU;
                double radius = Math.sqrt(random.nextDouble()) * slotEnvelope;
                int blockX = (int) Math.floor(center.x() + Math.cos(angle) * radius);
                int blockZ = (int) Math.floor(center.z() + Math.sin(angle) * radius);
                ChunkPos candidate = new ChunkPos(blockX >> 4, blockZ >> 4);
                WorldGeography.Site region = WorldGeography.siteAt(worldSeed, candidate.getMiddleBlockX(), candidate.getMiddleBlockZ());
                if (region.kind() != WorldGeography.Kind.CONTINENT || region.cellX() != cellX || region.cellZ() != cellZ) continue;
                if (isFarEnough(candidate, result, minimumDistanceBlocks)) {
                    result.add(candidate);
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Terra Kanto v1 has a fixed, finite land-mask rather than a seed-derived
     * atlas. These centres were sampled against that mask and are deliberately
     * spread across its lowlands, forest belt, dry coast, and mountain route.
     */
    /** Installs generator-validated sites before RoadWeaver's first pass. */
    public static void installTerraCandidates(long worldSeed, List<ChunkPos> candidates) {
        if (candidates.isEmpty() || candidates.size() > 64) {
            throw new IllegalArgumentException("Terra campaign town candidates must contain between one and 64 entries.");
        }
        TERRA_CANDIDATE_CACHE.put(worldSeed, List.copyOf(candidates));
    }

    /**
     * Makes Terra's generator-only site plan available before either BCA or
     * RoadWeaver asks a structure placement for candidates.  RoadWeaver may
     * legitimately skip its first scan when it has persisted state, so that
     * scan cannot be the only path which initializes these sites.
     */
    public static void ensureTerraCandidates(ServerLevel level) {
        if (!WorldGeography.isTerraWorld(level)
                || TERRA_CANDIDATE_CACHE.containsKey(level.getSeed())) return;
        installTerraCandidates(level.getSeed(), TerraTownSitePlanner.plan(level));
    }

    public static void clearTerraCandidates() {
        TERRA_CANDIDATE_CACHE.clear();
        TerraTownSitePlanner.clearPlanMetadata();
    }

    private List<ChunkPos> selectedTerraCandidates(long worldSeed) {
        List<ChunkPos> all = TERRA_CANDIDATE_CACHE.getOrDefault(worldSeed, List.of(
                // Keep the first, small-town slot within a short walk of the
                // vanilla spawn. StarterTownBootstrap materializes only this
                // bounded candidate before first join; the remaining network
                // stays continent-scale and is explored normally.
                new ChunkPos(512 >> 4, -512 >> 4), // starter lowland
                new ChunkPos(-3_000 >> 4, -5_000 >> 4),
                new ChunkPos(0, -5_000 >> 4),
                new ChunkPos(3_000 >> 4, -4_000 >> 4),
                new ChunkPos(-2_000 >> 4, -1_000 >> 4), // central hub
                new ChunkPos(2_000 >> 4, -1_000 >> 4),
                new ChunkPos(6_000 >> 4, 0),
                new ChunkPos(-5_000 >> 4, 2_000 >> 4),
                new ChunkPos(-2_000 >> 4, 4_000 >> 4), // League mountain route
                new ChunkPos(3_000 >> 4, 4_000 >> 4)
        ));
        int first = Math.min(slotStart, all.size());
        int configuredCount = Math.min(all.size(), townsPerContinent);
        int available = Math.max(0, configuredCount - first);
        int end = Math.min(all.size(), first + Math.min(slotCount, available));
        return all.subList(first, end);
    }

    private static boolean isFarEnough(ChunkPos candidate, List<ChunkPos> accepted, int minimumDistanceBlocks) {
        long minimumDistanceSquared = (long) minimumDistanceBlocks * minimumDistanceBlocks;
        for (ChunkPos other : accepted) {
            long dx = (long) candidate.getMiddleBlockX() - other.getMiddleBlockX();
            long dz = (long) candidate.getMiddleBlockZ() - other.getMiddleBlockZ();
            if (dx * dx + dz * dz < minimumDistanceSquared) return false;
        }
        return true;
    }

    /** Kanto's starter, central hub, and capstone are authored districts; the remaining seven slots are seeded. */
    private static ChunkPos kantoAnchor(int slot) {
        return switch (slot) {
            case 0 -> new ChunkPos(-4_160 >> 4, 3_680 >> 4); // southwest starter lowland
            case 4 -> new ChunkPos(160 >> 4, 160 >> 4);      // central hub
            case 8 -> new ChunkPos(-3_520 >> 4, -3_360 >> 4); // northwest League/capstone
            default -> null;
        };
    }

    private record SiteKey(long worldSeed, int cellX, int cellZ, int salt, int townCount, int separation) {}

    private record SlotRange(int start, int count) {}
}
