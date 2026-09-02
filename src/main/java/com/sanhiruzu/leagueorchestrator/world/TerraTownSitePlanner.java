package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import com.sanhiruzu.leagueorchestrator.data.LeagueDataReloadListener;
import com.sanhiruzu.leagueorchestrator.data.SettlementLayout;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Selects campaign towns against the active Terra generator before
 * RoadWeaver's first regional scan. The planner deliberately
 * reads only {@link ChunkGenerator#getBaseHeight}; it never requests chunks,
 * heightmaps, or structure starts.
 *
 * <p>The macro itinerary remains authored (starter, routes, hubs, capstone),
 * while each slot searches a bounded local area for a seed-stable, buildable
 * location. Two route slots prefer genuine water contact so the campaign gets
 * port/coastal towns without hard-coding exact shore coordinates.
 */
public final class TerraTownSitePlanner {
    private static final int SEA_LEVEL = 63;
    /**
     * BCA's overridden jigsaw towns may grow 116 blocks from their start.
     * Sample the centre, edges, and corners of that square. Terra base-height
     * evaluation constructs a generator sampler, so a per-chunk-column scan
     * made startup appear stuck before RoadWeaver could begin.
     */
    private static final int TOWN_FOOTPRINT_RADIUS = 112;
    private static final int TOWN_FOOTPRINT_SAMPLE_STEP = 56;
    private static final long SALT = 0x4B414E544F5F534CL;
    private static final ConcurrentHashMap<Long, PlanMetadata> PLAN_METADATA = new ConcurrentHashMap<>();

    // These establish Kanto's campaign topology only. The final positions are
    // seed-derived local candidates accepted from the active generator.
    private static final int[][] MACRO_ANCHORS = {
            {512, -512},
            {-3_000, -5_000},
            {0, -5_000},
            {3_000, -4_000},
            {-2_000, -1_000},
            {2_000, -1_000},
            {6_000, 0},
            {-5_000, 2_000},
            {-2_000, 4_000},
            {3_000, 4_000}
    };
    private static final boolean[] COASTAL_SLOTS = {
            // Kanto's route topology deliberately includes several ports and
            // coastal towns.  These are preferences, not exact coordinates:
            // each still has to earn a flat, above-water build site from the
            // active generator.
            false, true, false, true, false, false, true, true, false, false
    };

    private TerraTownSitePlanner() {
    }

    public static List<ChunkPos> plan(ServerLevel level) {
        if (!WorldGeography.isTerraWorld(level)) return List.of();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomSource random = RandomSource.create(level.getSeed() ^ SALT);
        SettlementLayout layout = LeagueDataReloadListener.current()
                .campaignForProfile(WorldGeography.profileAt(level, 0, 0))
                .map(com.sanhiruzu.leagueorchestrator.data.CampaignDefinition::settlementLayout)
                .orElse(SettlementLayout.KANTO_DEFAULT);
        double scale = layout.scaleFor(WorldGeography.cellSize(level));
        int townCount = layout.scaledLeagueTownTarget(scale);
        boolean preserveKantoTopology = layout.isKantoDefault() && townCount == MACRO_ANCHORS.length;
        int minimumSeparation = layout.scaledMinimumTownSpacing(scale);
        int averageSeparation = layout.scaledAverageTownSpacing(scale);
        int maximumSeparation = layout.scaledMaximumTownSpacing(scale);
        int searchRadius = layout.localSearchRadiusBlocks().scaleAverage(scale);
        int coastProbeDistance = layout.coastProbeDistanceBlocks().scaleAverage(scale);
        int maximumBuildHeight = layout.maximumBuildHeight().average();
        int maximumLocalRelief = layout.maximumLocalRelief().average();
        int maximumTownTerracingRelief = layout.maximumTownTerracingRelief().average();
        List<Candidate> accepted = new ArrayList<>(townCount + layout.incidentalSettlementCount().scaleByArea(scale));

        for (int slot = 0; slot < townCount; slot++) {
            Candidate best = null;
            boolean coastal = preserveKantoTopology ? COASTAL_SLOTS[slot] : coastalSlot(slot, townCount, layout);
            int attempts = coastal ? layout.coastalSearchAttempts().average() : layout.inlandSearchAttempts().average();
            for (int attempt = 0; attempt < attempts; attempt++) {
                int[] anchor = anchorFor(slot, level, layout, scale, averageSeparation);
                double angle = random.nextDouble() * Math.TAU;
                double radius = attempt == 0 ? 0.0D : Math.sqrt(random.nextDouble()) * searchRadius;
                int x = alignToChunk(anchor[0] + (int) Math.round(Math.cos(angle) * radius));
                int z = alignToChunk(anchor[1] + (int) Math.round(Math.sin(angle) * radius));
                Candidate candidate = inspect(generator, state, level, x, z, coastal, coastProbeDistance,
                        maximumBuildHeight, maximumTownTerracingRelief);
                if (candidate == null || !farEnough(candidate.chunk(), accepted, minimumSeparation)) continue;
                if (!preserveKantoTopology && !withinMaximumSpacing(candidate.chunk(), accepted, maximumSeparation)) continue;

                int score = candidate.score(coastal) + spacingScore(candidate.chunk(), accepted, averageSeparation);
                if (best == null || score > best.score(coastal) + spacingScore(best.chunk(), accepted, averageSeparation)) best = candidate;
            }
            if (best == null) {
                int[] anchor = anchorFor(slot, level, layout, scale, averageSeparation);
                best = searchWiderLowland(generator, state, level, slot, anchor, coastal, coastProbeDistance,
                        maximumBuildHeight, maximumTownTerracingRelief, accepted, minimumSeparation,
                        preserveKantoTopology ? Integer.MAX_VALUE : maximumSeparation);
                if (best != null) {
                    LeagueOrchestrator.LOGGER.info("Terra town slot {} used a wider generator-validated lowland search near {},{}.",
                            slot, anchor[0], anchor[1]);
                } else {
                    // A raw authored anchor can be a mountain or ocean after
                    // a pack changes its terrain.  Never feed that untested
                    // coordinate to BCA merely to retain a town count.
                    LeagueOrchestrator.LOGGER.warn("Terra town slot {} found no buildable candidate near {},{} or in its bounded wider search; skipping the unusable slot.",
                            slot, anchor[0], anchor[1]);
                    continue;
                }
            }
            accepted.add(best);
        }
        int leagueCount = accepted.size();
        int incidentalCount = layout.incidentalSettlementCount().scaleByArea(scale);
        int incidentalSpacing = layout.incidentalSettlementSpacingBlocks().scaleAverage(scale);
        int incidentalAttempts = layout.incidentalSearchAttempts().average();
        for (int incidental = 0; incidental < incidentalCount; incidental++) {
            Candidate best = null;
            int slot = townCount + incidental;
            for (int attempt = 0; attempt < incidentalAttempts; attempt++) {
                int[] anchor = incidentalAnchorFor(incidental, level, averageSeparation, incidentalSpacing);
                double angle = random.nextDouble() * Math.TAU;
                double radius = attempt == 0 ? 0.0D : Math.sqrt(random.nextDouble()) * searchRadius;
                int x = alignToChunk(anchor[0] + (int) Math.round(Math.cos(angle) * radius));
                int z = alignToChunk(anchor[1] + (int) Math.round(Math.sin(angle) * radius));
                Candidate candidate = inspect(generator, state, level, x, z, false, coastProbeDistance,
                        maximumBuildHeight, maximumTownTerracingRelief);
                if (candidate == null || !farEnough(candidate.chunk(), accepted, incidentalSpacing)) continue;
                int score = candidate.score(false) + spacingScore(candidate.chunk(), accepted, incidentalSpacing);
                if (best == null || score > best.score(false) + spacingScore(best.chunk(), accepted, incidentalSpacing)) best = candidate;
            }
            if (best == null) {
                int[] anchor = incidentalAnchorFor(incidental, level, averageSeparation, incidentalSpacing);
                best = searchWiderLowland(generator, state, level, slot, anchor, false, coastProbeDistance,
                        maximumBuildHeight, maximumTownTerracingRelief, accepted, incidentalSpacing,
                        Integer.MAX_VALUE);
                if (best != null) {
                    LeagueOrchestrator.LOGGER.info("Terra incidental settlement {} used a wider generator-validated terraced-site search.", slot);
                } else {
                    LeagueOrchestrator.LOGGER.warn("Terra incidental settlement {} found no safe buildable candidate; skipping it.", slot);
                    continue;
                }
            }
            accepted.add(best);
        }
        long coastal = accepted.stream().filter(candidate -> candidate.waterContacts() > 0).count();
        PLAN_METADATA.put(level.getSeed(), new PlanMetadata(leagueCount, accepted.size() - leagueCount,
                layout.incidentalRoadEndpointSharePermille()));
        LeagueOrchestrator.LOGGER.info("Selected {} Terra League town sites and {} incidental settlements ({} shoreline-adjacent, natural relief target {} blocks, terracing limit {} blocks, League spacing {}–{}–{} blocks, scale {}).",
                leagueCount, accepted.size() - leagueCount, coastal, maximumLocalRelief, maximumTownTerracingRelief, minimumSeparation, averageSeparation, maximumSeparation,
                String.format(java.util.Locale.ROOT, "%.2f", scale));
        return accepted.stream().map(Candidate::chunk).toList();
    }

    /** Number of leading candidates reserved for the campaign's progression. */
    public static int leagueTownCount(long worldSeed) {
        return PLAN_METADATA.getOrDefault(worldSeed,
                new PlanMetadata(10, 0, SettlementLayout.KANTO_DEFAULT.incidentalRoadEndpointSharePermille())).leagueTownCount();
    }

    /**
     * Incidental villages are real structures, but only a deterministic share
     * participates in RoadWeaver's initial macro graph. This preserves the
     * lived-in look without making every hamlet a continent-scale road hub.
     */
    public static boolean isIncidentalRoadEndpoint(long worldSeed, int incidentalIndex) {
        PlanMetadata metadata = PLAN_METADATA.get(worldSeed);
        if (metadata == null || incidentalIndex < 0 || incidentalIndex >= metadata.incidentalCount()) return false;
        SettlementLayout.Range share = metadata.roadEndpointSharePermille();
        int minimum = (int) Math.ceil(metadata.incidentalCount() * share.minimum() / 1_000.0D);
        int maximum = Math.max(minimum, (int) Math.floor(metadata.incidentalCount() * share.maximum() / 1_000.0D));
        int target = Math.clamp((int) Math.round(metadata.incidentalCount() * share.average() / 1_000.0D), minimum, maximum);
        return Math.floorMod(incidentalIndex * 7, metadata.incidentalCount()) < target;
    }

    public static void clearPlanMetadata() {
        PLAN_METADATA.clear();
    }

    private static Candidate inspect(ChunkGenerator generator, ChunkGeneratorStructureState state, ServerLevel level,
                                     int x, int z, boolean inspectCoast, int coastProbeDistance,
                                     int maximumBuildHeight, int maximumLocalRelief) {
        int center = baseHeight(generator, state, level, x, z);
        if (center < SEA_LEVEL + 2 || center > maximumBuildHeight) return null;

        int minimum = center;
        int maximum = center;
        int waterContacts = 0;
        for (int offsetX = -TOWN_FOOTPRINT_RADIUS; offsetX <= TOWN_FOOTPRINT_RADIUS;
             offsetX += TOWN_FOOTPRINT_SAMPLE_STEP) {
            for (int offsetZ = -TOWN_FOOTPRINT_RADIUS; offsetZ <= TOWN_FOOTPRINT_RADIUS;
                 offsetZ += TOWN_FOOTPRINT_SAMPLE_STEP) {
                int height = baseHeight(generator, state, level, x + offsetX, z + offsetZ);
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
            }
        }
        int localRelief = maximum - minimum;
        if (localRelief > maximumLocalRelief) return null;
        if (inspectCoast) {
            for (int[] offset : new int[][]{
                {coastProbeDistance, 0}, {-coastProbeDistance, 0},
                {0, coastProbeDistance}, {0, -coastProbeDistance}
            }) {
                if (baseHeight(generator, state, level, x + offset[0], z + offset[1]) <= SEA_LEVEL + 1) waterContacts++;
            }
        }
        return new Candidate(new ChunkPos(x >> 4, z >> 4), center, localRelief, waterContacts);
    }

    private static int baseHeight(ChunkGenerator generator, ChunkGeneratorStructureState state, ServerLevel level, int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, state.randomState());
    }

    /**
     * Recovers an authored campaign slot whose immediate macro anchor is on a
     * cliff, water, or a landmark mountain.  This is still seed-stable and
     * generator-only; it widens the search rather than accepting an invalid
     * coordinate and materializing a town high inside terrain.
     */
    private static Candidate searchWiderLowland(ChunkGenerator generator, ChunkGeneratorStructureState state,
                                                ServerLevel level, int slot, int[] anchor, boolean coastal,
                                                int coastProbeDistance, int maximumBuildHeight,
                                                int maximumLocalRelief, List<Candidate> accepted,
                                                int minimumSeparation, int maximumSeparation) {
        Candidate best = null;
        int envelope = WorldGeography.envelope(level);
        // Base-height probes into a modded Terra generator are materially
        // more expensive than ordinary noise samples. Eight evenly spaced
        // directions locate a viable nearby lowland without introducing a
        // multi-second server-start hitch for every failed macro anchor.
        for (int ring = 1; ring <= 6; ring++) {
            int radius = Math.min(envelope - 256, 768 + ring * 768);
            for (int spoke = 0; spoke < 8; spoke++) {
                double angle = Math.TAU * (spoke / 8.0D + slot * 0.3819660112501051D + ring * 0.071D);
                int x = alignToChunk(anchor[0] + (int) Math.round(Math.cos(angle) * radius));
                int z = alignToChunk(anchor[1] + (int) Math.round(Math.sin(angle) * radius));
                Candidate candidate = inspect(generator, state, level, x, z, coastal, coastProbeDistance,
                        maximumBuildHeight, maximumLocalRelief);
                if (candidate == null || !farEnough(candidate.chunk(), accepted, minimumSeparation)
                        || !withinMaximumSpacing(candidate.chunk(), accepted, maximumSeparation)) continue;
                if (best == null || candidate.score(coastal) + spacingScore(candidate.chunk(), accepted, minimumSeparation)
                        > best.score(coastal) + spacingScore(best.chunk(), accepted, minimumSeparation)) {
                    best = candidate;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static int alignToChunk(int coordinate) {
        return Math.floorDiv(coordinate, 16) * 16 + 8;
    }

    private static boolean farEnough(ChunkPos candidate, List<Candidate> accepted, int minimumSeparation) {
        long minimumSquared = (long) minimumSeparation * minimumSeparation;
        for (Candidate acceptedCandidate : accepted) {
            ChunkPos other = acceptedCandidate.chunk();
            long dx = (long) candidate.getMiddleBlockX() - other.getMiddleBlockX();
            long dz = (long) candidate.getMiddleBlockZ() - other.getMiddleBlockZ();
            if (dx * dx + dz * dz < minimumSquared) return false;
        }
        return true;
    }

    private static int[] anchorFor(int slot, ServerLevel level, SettlementLayout layout, double scale, int averageSpacing) {
        if (slot < MACRO_ANCHORS.length) {
            return new int[]{
                    (int) Math.round(MACRO_ANCHORS[slot][0] * scale),
                    (int) Math.round(MACRO_ANCHORS[slot][1] * scale)
            };
        }
        // Beyond the authored Kanto itinerary, use a deterministic sunflower
        // spread. Custom content packs can raise the town range without
        // inheriting a hidden fixed coordinate table.
        double angle = slot * 2.399963229728653D;
        double radius = Math.min(WorldGeography.envelope(level) * 0.72D,
                averageSpacing * Math.sqrt(slot - MACRO_ANCHORS.length + 2.0D));
        return new int[]{(int) Math.round(Math.cos(angle) * radius), (int) Math.round(Math.sin(angle) * radius)};
    }

    private static boolean coastalSlot(int slot, int townCount, SettlementLayout layout) {
        int minimum = (int) Math.ceil(townCount * layout.coastalTownSharePermille().minimum() / 1_000.0D);
        int maximum = Math.max(minimum, (int) Math.floor(townCount * layout.coastalTownSharePermille().maximum() / 1_000.0D));
        int desired = Math.clamp((int) Math.round(townCount * layout.coastalTownSharePermille().average() / 1_000.0D),
                minimum, maximum);
        return Math.floorMod(slot * 7, townCount) < desired;
    }

    private static int[] incidentalAnchorFor(int slot, ServerLevel level, int leagueSpacing, int incidentalSpacing) {
        // This is deliberately a different phase from the League itinerary:
        // it fills inhabited gaps instead of turning every route hub into a
        // town. Candidate acceptance still enforces the data-pack spacing.
        double angle = (slot + 0.5D) * 2.399963229728653D + 0.73D;
        double radius = Math.min(WorldGeography.envelope(level) * 0.76D,
                leagueSpacing * 1.25D + incidentalSpacing * Math.sqrt(slot + 1.0D));
        return new int[]{(int) Math.round(Math.cos(angle) * radius), (int) Math.round(Math.sin(angle) * radius)};
    }

    private static boolean withinMaximumSpacing(ChunkPos candidate, List<Candidate> accepted, int maximumSpacing) {
        if (accepted.isEmpty()) return true;
        long maximumSquared = (long) maximumSpacing * maximumSpacing;
        return accepted.stream().anyMatch(other -> {
            long dx = (long) candidate.getMiddleBlockX() - other.chunk().getMiddleBlockX();
            long dz = (long) candidate.getMiddleBlockZ() - other.chunk().getMiddleBlockZ();
            return dx * dx + dz * dz <= maximumSquared;
        });
    }

    private static int spacingScore(ChunkPos candidate, List<Candidate> accepted, int averageSpacing) {
        if (accepted.isEmpty()) return 0;
        double nearest = accepted.stream().mapToDouble(other -> {
            double dx = candidate.getMiddleBlockX() - other.chunk().getMiddleBlockX();
            double dz = candidate.getMiddleBlockZ() - other.chunk().getMiddleBlockZ();
            return Math.hypot(dx, dz);
        }).min().orElse(averageSpacing);
        return Math.max(-96, 48 - (int) Math.round(Math.abs(nearest - averageSpacing) / 24.0D));
    }

    private record Candidate(ChunkPos chunk, int height, int localRelief, int waterContacts) {
        private int score(boolean preferCoast) {
            int flatness = 48 - localRelief * 4;
            int lowland = Math.max(0, 96 - height);
            int coast = waterContacts == 0 ? 0 : 160 + Math.min(3, waterContacts) * 12;
            return flatness + lowland + (preferCoast ? coast : -coast / 3);
        }
    }

    private record PlanMetadata(int leagueTownCount, int incidentalCount,
                                SettlementLayout.Range roadEndpointSharePermille) {}
}
