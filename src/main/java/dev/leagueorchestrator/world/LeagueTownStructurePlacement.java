package dev.leagueorchestrator.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.regionengine.config.AtlasConfig;
import dev.regionengine.world.atlas.AtlasMath;
import dev.regionengine.world.atlas.AtlasRegion;
import net.minecraft.core.Vec3i;
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
 * Region Engine continent. Minecraft, League, and RoadWeaver enumerate the
 * exact same slots without loading terrain.
 */
public final class LeagueTownStructurePlacement extends StructurePlacement {
    private static final int MAX_ATTEMPTS_PER_TOWN = 128;
    private static final int MAX_CACHE_ENTRIES = 4_096;
    private static final ConcurrentHashMap<SiteKey, List<ChunkPos>> CANDIDATE_CACHE = new ConcurrentHashMap<>();
    private static final MapCodec<SlotRange> SLOT_RANGE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(0, 63).optionalFieldOf("slot_start", 0).forGetter(SlotRange::start),
            Codec.intRange(1, 64).optionalFieldOf("slot_count", 64).forGetter(SlotRange::count)
    ).apply(instance, SlotRange::new));

    public static final MapCodec<LeagueTownStructurePlacement> CODEC = RecordCodecBuilder
            .<LeagueTownStructurePlacement>mapCodec(instance -> placementCodec(instance).and(instance.group(
                    Codec.intRange(1, 64).optionalFieldOf("towns_per_continent", 10)
                            .forGetter(LeagueTownStructurePlacement::townsPerContinent),
                    Codec.intRange(0, 512).optionalFieldOf("minimum_separation", 16)
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

    /** Enumerates candidate chunks in the supplied inclusive chunk rectangle. */
    public List<ChunkPos> potentialChunks(long worldSeed, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        AtlasConfig config = AtlasConfig.get();
        // Cell centres live on block-space multiples of cellSize, whereas
        // queries use chunks. Enumerate one neighbouring cell on each side
        // rather than approximating the division in chunk space; this avoids
        // a boundary miss whenever cellSize is not divisible by 16.
        int minCellX = Math.floorDiv(minChunkX * 16, config.cellSize) - 1;
        int maxCellX = Math.floorDiv(maxChunkX * 16, config.cellSize) + 1;
        int minCellZ = Math.floorDiv(minChunkZ * 16, config.cellSize) - 1;
        int maxCellZ = Math.floorDiv(maxChunkZ * 16, config.cellSize) + 1;
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
        ChunkPos current = new ChunkPos(chunkX, chunkZ);
        AtlasRegion region = AtlasMath.classify(state.getLevelSeed(), current.getMiddleBlockX(), current.getMiddleBlockZ());
        return region.kind() == AtlasRegion.Kind.CONTINENT
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
        AtlasConfig config = AtlasConfig.get();
        AtlasMath.SiteCenter center = AtlasMath.siteCenter(worldSeed, cellX, cellZ);
        double envelope = config.maxRadius + config.maxCoastProtrusion;
        int minimumDistanceBlocks = minimumSeparation * 16;
        long seed = worldSeed ^ salt() ^ ((long) cellX * 0x9E3779B97F4A7C15L)
                ^ ((long) cellZ * 0xC2B2AE3D27D4EB4FL);
        RandomSource random = RandomSource.create(seed);
        List<ChunkPos> result = new ArrayList<>(townsPerContinent);
        for (int slot = 0; slot < townsPerContinent; slot++) {
            // Reserve several interior slots. Vanilla's initial spawn is near
            // the Atlas site's centre, so this gives the starter bootstrap
            // multiple normal BCA candidates before it considers the outer
            // settlement ring; it is still pure seed math, not a spawn hook.
            double slotEnvelope = slot < Math.min(3, townsPerContinent) ? envelope * 0.35 : envelope;
            for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_TOWN; attempt++) {
                double angle = random.nextDouble() * Math.TAU;
                double radius = Math.sqrt(random.nextDouble()) * slotEnvelope;
                int blockX = (int) Math.floor(center.x() + Math.cos(angle) * radius);
                int blockZ = (int) Math.floor(center.z() + Math.sin(angle) * radius);
                ChunkPos candidate = new ChunkPos(blockX >> 4, blockZ >> 4);
                AtlasRegion region = AtlasMath.classify(worldSeed, candidate.getMiddleBlockX(), candidate.getMiddleBlockZ());
                if (region.kind() != AtlasRegion.Kind.CONTINENT || region.cellX() != cellX || region.cellZ() != cellZ) continue;
                if (isFarEnough(candidate, result, minimumDistanceBlocks)) {
                    result.add(candidate);
                    break;
                }
            }
        }
        return List.copyOf(result);
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

    private record SiteKey(long worldSeed, int cellX, int cellZ, int salt, int townCount, int separation) {}

    private record SlotRange(int start, int count) {}
}
