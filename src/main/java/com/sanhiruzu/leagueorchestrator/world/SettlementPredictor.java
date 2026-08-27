package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import com.sanhiruzu.regionengine.config.AtlasConfig;
import com.sanhiruzu.regionengine.world.atlas.AtlasRegion;
import com.sanhiruzu.regionengine.policy.RegionPolicyService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
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
        ServerLevel level = LeagueMount.level(server);
        if (RoadWeaverPlacementAdapterBridge.isInstalled()) {
            // RoadWeaver calls the integration after its own initial regional
            // pass. Do not race it with a second structure-set scan here.
            return;
        }
        AtlasRegion site = RegionPolicyService.atlasRegionAt(level.getSeed(), level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ());
        if (site.kind() != AtlasRegion.Kind.CONTINENT) return;

        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        if (!plan.isSitePlanned(site.cellX(), site.cellZ())) {
            // This is the one bounded start-up exception to background
            // prediction. It performs only pure placement math, before an
            // integrated/dedicated server accepts its first player, so the
            // starter town can establish the actual shared spawn in time.
            Snapshot snapshot = Snapshot.capture(level, site.cellX(), site.cellZ());
            try {
                applyPrediction(level, snapshot, predict(snapshot), null);
            } catch (Throwable throwable) {
                LeagueOrchestrator.LOGGER.warn("Could not synchronously predict the initial starter site {},{}.",
                        site.cellX(), site.cellZ(), throwable);
            }
        }
        StarterTownBootstrap.materializeBeforeFirstJoin(level);
    }

    /** Plans a site once, without generating any of its chunks. Safe to call every player tick. */
    public static void requestSite(ServerLevel level, int siteX, int siteZ) {
        if (!LeagueMount.isMounted(level)) return;
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
        if (RoadWeaverPlacementAdapterBridge.isInstalled()) return;
        ServerLevel level = LeagueMount.level(server);
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
                TownStartPrewarmer.enqueue(level, anchor.get());
            }
        }
        // Priority is a scheduling decision only. The selected town still
        // enters through the same Minecraft structure-placement pipeline.
        StarterTownBootstrap.considerPlannedTowns(level);
        // BCA jigsaws can still reject a terrain-dependent candidate. Keep
        // the candidate solely as a persistent town identity; its road node,
        // gym, and route edges are created only by SettlementRealization.
        // RoadWeaver treats every endpoint as a protected structure location,
        // so publishing an ungenerated candidate would create a phantom town.
        plan.markSitePlanned(snapshot.siteX(), snapshot.siteZ());
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
            if (placement instanceof LeagueTownStructurePlacement leagueTown) {
                leagueTown.potentialChunks(snapshot.seed(), snapshot.minChunkX(), snapshot.minChunkZ(),
                                snapshot.maxChunkX(), snapshot.maxChunkZ())
                        .forEach(candidate -> addCandidate(snapshot, set, placement, candidate, predicted));
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

        CandidateStructure structure = set.structureFor(snapshot.seed(), candidate);
        if (structure == null || !structure.settlement()) return;

        String key = structure.id() + "@" + candidate.x + "," + candidate.z;
        result.putIfAbsent(key, new PredictedSettlement(structure.id(), placement.getLocatePos(candidate)));
    }

    private static String siteKey(int siteX, int siteZ) {
        return siteX + "," + siteZ;
    }

    private record PredictedSettlement(ResourceLocation structureId, BlockPos position) {}

    /**
     * A complete StructureSet entry, rather than only League-tagged entries.
     * Vanilla selects from every entry in a set before it knows whether the
     * selected structure belongs to League's settlement tag.
     */
    private record CandidateStructure(ResourceLocation id, int weight, boolean settlement) {}

    private record SetPlan(StructurePlacement placement, List<CandidateStructure> structures) {
        /**
         * Mirrors ChunkGenerator's initial StructureSet choice: a legacy
         * WorldgenRandom seeded with the world seed and candidate chunk, then
         * one weighted roll in registry/data-pack order.  Do not replace this
         * with a biome match or an unordered collection; either changes the
         * chosen BCA variant and poisons the persistent town identity.
         */
        private CandidateStructure structureFor(long worldSeed, ChunkPos candidate) {
            int totalWeight = structures.stream().mapToInt(CandidateStructure::weight).sum();
            if (totalWeight <= 0) return null;

            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            random.setLargeFeatureSeed(worldSeed, candidate.x, candidate.z);
            int roll = random.nextInt(totalWeight);
            for (CandidateStructure structure : structures) {
                roll -= structure.weight();
                if (roll < 0) return structure;
            }
            return null;
        }
    }

    private record Snapshot(long seed, int siteX, int siteZ, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                            ChunkGeneratorStructureState structureState, List<SetPlan> structureSets) {
        private static Snapshot capture(ServerLevel level, int siteX, int siteZ) {
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            List<SetPlan> plans = new ArrayList<>();
            for (Holder<StructureSet> setHolder : state.possibleStructureSets()) {
                List<CandidateStructure> structures = new ArrayList<>();
                for (StructureSet.StructureSelectionEntry entry : setHolder.value().structures()) {
                    Holder<Structure> structure = entry.structure();
                    Optional<ResourceKey<Structure>> key = structure.unwrapKey();
                    key.ifPresent(resourceKey -> structures.add(new CandidateStructure(
                            resourceKey.location(), entry.weight(), structure.is(SETTLEMENTS))));
                }
                if (structures.stream().anyMatch(CandidateStructure::settlement)) {
                    plans.add(new SetPlan(setHolder.value().placement(), List.copyOf(structures)));
                }
            }

            int cellSize = AtlasConfig.get().cellSize;
            int halfCell = cellSize / 2;
            int minimumBlock = siteX * cellSize - halfCell;
            int minimumBlockZ = siteZ * cellSize - halfCell;
            int maximumBlock = minimumBlock + cellSize - 1;
            int maximumBlockZ = minimumBlockZ + cellSize - 1;
            return capture(level, Math.floorDiv(minimumBlock, 16), Math.floorDiv(minimumBlockZ, 16),
                    Math.floorDiv(maximumBlock, 16), Math.floorDiv(maximumBlockZ, 16), state, plans, siteX, siteZ);
        }

        private static Snapshot capture(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            List<SetPlan> plans = collectPlans(state);
            AtlasRegion site = RegionPolicyService.atlasRegionAt(level.getSeed(), level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ());
            return capture(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ, state, plans, site.cellX(), site.cellZ());
        }

        private static Snapshot capture(ServerLevel level, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                        ChunkGeneratorStructureState state, List<SetPlan> plans, int siteX, int siteZ) {
            return new Snapshot(level.getSeed(), siteX, siteZ, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                    state, List.copyOf(plans));
        }

        private static List<SetPlan> collectPlans(ChunkGeneratorStructureState state) {
            List<SetPlan> plans = new ArrayList<>();
            for (Holder<StructureSet> setHolder : state.possibleStructureSets()) {
                List<CandidateStructure> structures = new ArrayList<>();
                for (StructureSet.StructureSelectionEntry entry : setHolder.value().structures()) {
                    Holder<Structure> structure = entry.structure();
                    Optional<ResourceKey<Structure>> key = structure.unwrapKey();
                    key.ifPresent(resourceKey -> structures.add(new CandidateStructure(
                            resourceKey.location(), entry.weight(), structure.is(SETTLEMENTS))));
                }
                if (structures.stream().anyMatch(CandidateStructure::settlement)) {
                    plans.add(new SetPlan(setHolder.value().placement(), List.copyOf(structures)));
                }
            }
            return List.copyOf(plans);
        }
    }
}
