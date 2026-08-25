package dev.leagueorchestrator.world;

import dev.leagueorchestrator.LeagueOrchestrator;
import dev.regionengine.config.AtlasConfig;
import dev.regionengine.world.atlas.AtlasRegion;
import dev.regionengine.policy.RegionPolicyService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Predicts settlement structure starts directly from vanilla's structure-set
 * placement state. This is the same non-generating approach used by
 * RoadWeaver's predictor: no chunks, heightmaps, or structure starts are read
 * while a site is being planned.
 */
public final class SettlementPredictor {
    public static final TagKey<Structure> SETTLEMENTS = TagKey.create(
            Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(LeagueOrchestrator.MOD_ID, "settlements"));
    private static final ExecutorService PLANNER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "League-Settlement-Planner");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> PENDING_SITES = new HashSet<>();

    private SettlementPredictor() {}

    /** Plans the full atlas site containing spawn before any exploration is needed. */
    public static void planSpawnSite(MinecraftServer server) {
        ServerLevel level = server.overworld();
        AtlasRegion site = RegionPolicyService.atlasRegionAt(level.getSeed(), level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ());
        if (site.kind() == AtlasRegion.Kind.CONTINENT) requestSite(level, site.cellX(), site.cellZ());
    }

    /** Plans a site once, without generating any of its chunks. Safe to call every player tick. */
    public static void requestSite(ServerLevel level, int siteX, int siteZ) {
        if (level != level.getServer().overworld()) return;
        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        String key = siteKey(siteX, siteZ);
        synchronized (PENDING_SITES) {
            if (plan.isSitePlanned(siteX, siteZ) || !PENDING_SITES.add(key)) return;
        }

        Snapshot snapshot = Snapshot.capture(level, siteX, siteZ);
        CompletableFuture.supplyAsync(() -> predict(snapshot), PLANNER).whenComplete((settlements, error) ->
                level.getServer().execute(() -> applyPrediction(level, snapshot, settlements, error)));
    }

    /** Expands the plan as players enter new atlas sites; this is not a chunk or structure event. */
    public static void planPlayerSites(MinecraftServer server) {
        ServerLevel level = server.overworld();
        server.getPlayerList().getPlayers().forEach(player -> {
            if (player.serverLevel() != level) return;
            AtlasRegion site = RegionPolicyService.atlasRegionAt(level.getSeed(), player.getX(), player.getZ());
            if (site.kind() == AtlasRegion.Kind.CONTINENT) requestSite(level, site.cellX(), site.cellZ());
        });
    }

    public static void stop() {
        // An integrated client can stop one server and create another without
        // restarting its JVM. Keep this daemon executor reusable; it cannot
        // keep Minecraft alive during process shutdown.
        synchronized (PENDING_SITES) {
            PENDING_SITES.clear();
        }
    }

    private static void applyPrediction(ServerLevel level, Snapshot snapshot, List<PredictedSettlement> settlements, Throwable error) {
        synchronized (PENDING_SITES) {
            PENDING_SITES.remove(siteKey(snapshot.siteX(), snapshot.siteZ()));
        }
        if (level.getServer().isStopped()) return;
        if (error != null) {
            LeagueOrchestrator.LOGGER.warn("Could not predict settlements for atlas site {},{}.", snapshot.siteX(), snapshot.siteZ(), error);
            return;
        }

        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        int planned = 0;
        for (PredictedSettlement settlement : settlements) {
            Optional<TownAnchor> anchor = plan.planSettlement(level, settlement.structureId(), settlement.position());
            if (anchor.isPresent()) {
                planned++;
                // Reserve every site-local gym before terrain generation. The
                // later realization pass only supplies a safe physical lot.
                if (AutoTownGymPlacement.isGymEligible(level, anchor.get())) {
                    plan.campaignFor(level, anchor.get().center()).map(dev.leagueorchestrator.data.CampaignDefinition::gymPool)
                            .filter(pool -> !pool.isBlank())
                            .ifPresent(pool -> plan.gymForTown(level, anchor.get(), pool));
                }
            }
        }
        plan.ensureSiteRoadGraph(snapshot.siteX(), snapshot.siteZ());
        plan.markSitePlanned(snapshot.siteX(), snapshot.siteZ());
        plan.publishRoadWeaverCampaign(level);
        // Spawn chunks may have loaded before the asynchronous prediction
        // finishes. Reconcile only chunks already resident in memory; never
        // request a chunk from this planning path.
        for (PredictedSettlement settlement : settlements) {
            LevelChunk loaded = level.getChunkSource().getChunkNow(
                    settlement.position().getX() >> 4, settlement.position().getZ() >> 4);
            if (loaded != null) SettlementRealization.realize(level, loaded);
        }
        LeagueOrchestrator.LOGGER.info("Predicted {} tagged settlements for atlas site {},{} without generating chunks.",
                planned, snapshot.siteX(), snapshot.siteZ());
    }

    private static List<PredictedSettlement> predict(Snapshot snapshot) {
        Map<String, PredictedSettlement> predicted = new LinkedHashMap<>();
        for (SetPlan set : snapshot.structureSets()) {
            StructurePlacement placement = set.placement();
            if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> positions = snapshot.structureState().getRingPositionsFor(rings);
                if (positions == null) continue;
                for (ChunkPos chunk : positions) addCandidate(snapshot, set, placement, chunk, predicted);
                continue;
            }
            if (!(placement instanceof RandomSpreadStructurePlacement randomSpread)) continue;

            int spacing = randomSpread.spacing();
            for (int gridX = Math.floorDiv(snapshot.minChunkX(), spacing); gridX <= Math.floorDiv(snapshot.maxChunkX(), spacing); gridX++) {
                for (int gridZ = Math.floorDiv(snapshot.minChunkZ(), spacing); gridZ <= Math.floorDiv(snapshot.maxChunkZ(), spacing); gridZ++) {
                    ChunkPos candidate = randomSpread.getPotentialStructureChunk(snapshot.seed(), gridX * spacing, gridZ * spacing);
                    addCandidate(snapshot, set, placement, candidate, predicted);
                }
            }
        }
        return predicted.values().stream()
                .sorted(Comparator.comparingInt((PredictedSettlement value) -> value.position().getX())
                        .thenComparingInt(value -> value.position().getZ())
                        .thenComparing(value -> value.structureId().toString()))
                .toList();
    }

    private static void addCandidate(Snapshot snapshot, SetPlan set, StructurePlacement placement, ChunkPos candidate,
                                     Map<String, PredictedSettlement> result) {
        if (candidate.x < snapshot.minChunkX() || candidate.x > snapshot.maxChunkX()
                || candidate.z < snapshot.minChunkZ() || candidate.z > snapshot.maxChunkZ()) return;
        if (!placement.isStructureChunk(snapshot.structureState(), candidate.x, candidate.z)) return;

        BlockPos position = placement.getLocatePos(candidate);
        Holder<Biome> biome = snapshot.biomeSource().getNoiseBiome(
                QuartPos.fromBlock(position.getX()), QuartPos.fromBlock(64), QuartPos.fromBlock(position.getZ()),
                snapshot.randomState().sampler());
        CandidateStructure structure = set.structureFor(biome);
        if (structure == null) return;

        String key = structure.id() + "@" + candidate.x + "," + candidate.z;
        result.putIfAbsent(key, new PredictedSettlement(structure.id(), position));
    }

    private static String siteKey(int siteX, int siteZ) {
        return siteX + "," + siteZ;
    }

    private record PredictedSettlement(ResourceLocation structureId, BlockPos position) {}

    private record CandidateStructure(ResourceLocation id, Set<Holder<Biome>> biomes) {}

    private record SetPlan(StructurePlacement placement, List<CandidateStructure> structures) {
        private CandidateStructure structureFor(Holder<Biome> biome) {
            return structures.stream().filter(structure -> structure.biomes().contains(biome)).findFirst().orElse(null);
        }
    }

    private record Snapshot(long seed, int siteX, int siteZ, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                            ChunkGeneratorStructureState structureState, RandomState randomState, BiomeSource biomeSource,
                            List<SetPlan> structureSets) {
        private static Snapshot capture(ServerLevel level, int siteX, int siteZ) {
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            List<SetPlan> plans = new ArrayList<>();
            for (Holder<StructureSet> setHolder : state.possibleStructureSets()) {
                List<CandidateStructure> structures = new ArrayList<>();
                for (StructureSet.StructureSelectionEntry entry : setHolder.value().structures()) {
                    Holder<Structure> structure = entry.structure();
                    Optional<ResourceKey<Structure>> key = structure.unwrapKey();
                    if (key.isPresent() && structure.is(SETTLEMENTS)) {
                        Set<Holder<Biome>> biomes = new HashSet<>();
                        structure.value().biomes().forEach(biomes::add);
                        structures.add(new CandidateStructure(key.get().location(), Set.copyOf(biomes)));
                    }
                }
                if (!structures.isEmpty()) plans.add(new SetPlan(setHolder.value().placement(), List.copyOf(structures)));
            }

            int cellSize = AtlasConfig.get().cellSize;
            int halfCell = cellSize / 2;
            int minimumBlock = siteX * cellSize - halfCell;
            int minimumBlockZ = siteZ * cellSize - halfCell;
            int maximumBlock = minimumBlock + cellSize - 1;
            int maximumBlockZ = minimumBlockZ + cellSize - 1;
            return new Snapshot(level.getSeed(), siteX, siteZ,
                    Math.floorDiv(minimumBlock, 16), Math.floorDiv(minimumBlockZ, 16),
                    Math.floorDiv(maximumBlock, 16), Math.floorDiv(maximumBlockZ, 16),
                    state, state.randomState(), level.getChunkSource().getGenerator().getBiomeSource(), List.copyOf(plans));
        }
    }
}
