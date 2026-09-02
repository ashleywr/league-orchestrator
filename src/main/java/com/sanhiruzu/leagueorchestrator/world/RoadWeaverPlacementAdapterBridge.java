package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import com.sanhiruzu.leagueorchestrator.data.CampaignDefinition;
import com.sanhiruzu.leagueorchestrator.data.GymArchetype;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Optional bridge to RoadWeaver's pure placement-adapter API. RoadWeaver owns
 * the regional discovery/planning pass; League contributes only enumeration
 * for its custom placement type. Reflection keeps the hard League runtime
 * independent from one particular RoadWeaver build.
 */
public final class RoadWeaverPlacementAdapterBridge {
    private static final String API_CLASS = "net.shiroha233.roadweaver.api.RoadNetworkApi";
    private static final String ADAPTER_CLASS = "net.shiroha233.roadweaver.api.StructurePlacementAdapter";
    private static final String INITIAL_LISTENER_CLASS = "net.shiroha233.roadweaver.api.InitialPlanningListener";
    private static final String INITIAL_BOUNDS_PROVIDER_CLASS = "net.shiroha233.roadweaver.api.InitialPlanningBoundsProvider";
    private static final String INITIAL_CANDIDATE_PROVIDER_CLASS = "net.shiroha233.roadweaver.api.InitialPlanningCandidateProvider";
    private static final String INITIAL_BOUNDS_CLASS = "net.shiroha233.roadweaver.api.InitialPlanningBounds";
    private static final String STRUCTURE_INFO_CLASS = "net.shiroha233.roadweaver.core.model.StructureInfo";
    private static final String MAP_LOCATION_ICON_CLASS = "net.shiroha233.roadweaver.core.model.MapLocationIcon";
    private static final String MAP_LOCATION_ICON_KIND_CLASS = "net.shiroha233.roadweaver.core.model.MapLocationIcon$Kind";
    private static boolean installed;
    private static boolean unavailable;
    private static Object adapter;
    private static Object initialPlanningListener;
    private static Object initialPlanningBoundsProvider;
    private static Object initialPlanningCandidateProvider;
    private static boolean reportedExclusivePolicy;

    private RoadWeaverPlacementAdapterBridge() {}

    public static synchronized void install() {
        if (installed || unavailable) return;
        try {
            ClassLoader loader = RoadWeaverPlacementAdapterBridge.class.getClassLoader();
            Class<?> adapterType = Class.forName(ADAPTER_CLASS, false, loader);
            Class<?> listenerType = Class.forName(INITIAL_LISTENER_CLASS, false, loader);
            Class<?> apiType = Class.forName(API_CLASS, false, loader);
            InvocationHandler handler = RoadWeaverPlacementAdapterBridge::invoke;
            adapter = Proxy.newProxyInstance(loader, new Class<?>[]{adapterType}, handler);
            initialPlanningListener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType},
                    RoadWeaverPlacementAdapterBridge::invokeInitialPlanningListener);
            apiType.getMethod("registerStructurePlacementAdapter", adapterType).invoke(null, adapter);
            apiType.getMethod("registerInitialPlanningListener", listenerType).invoke(null, initialPlanningListener);
            installInitialBoundsProvider(loader, apiType);
            installInitialCandidateProvider(loader, apiType);
            installed = true;
            LeagueOrchestrator.LOGGER.info("Registered League planned-town placement with RoadWeaver's regional planner.");
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            LeagueOrchestrator.LOGGER.info("RoadWeaver lacks the League placement-adapter API; retaining the realized-endpoint fallback.");
        }
    }

    /** Newer RoadWeaver builds can make the first pass match League's active geography site. */
    private static void installInitialBoundsProvider(ClassLoader loader, Class<?> apiType) {
        try {
            Class<?> providerType = Class.forName(INITIAL_BOUNDS_PROVIDER_CLASS, false, loader);
            initialPlanningBoundsProvider = Proxy.newProxyInstance(loader, new Class<?>[]{providerType},
                    RoadWeaverPlacementAdapterBridge::invokeInitialPlanningBoundsProvider);
            apiType.getMethod("registerInitialPlanningBoundsProvider", providerType).invoke(null, initialPlanningBoundsProvider);
            LeagueOrchestrator.LOGGER.info("Registered League geography bounds with RoadWeaver's initial planner.");
        } catch (ReflectiveOperationException exception) {
            LeagueOrchestrator.LOGGER.info("RoadWeaver lacks the initial-bounds API; using its configured initial radius.");
        }
    }

    /** Supplies OTG-safe pure candidates before RoadWeaver computes its graph. */
    private static void installInitialCandidateProvider(ClassLoader loader, Class<?> apiType) {
        try {
            Class<?> providerType = Class.forName(INITIAL_CANDIDATE_PROVIDER_CLASS, false, loader);
            initialPlanningCandidateProvider = Proxy.newProxyInstance(loader, new Class<?>[]{providerType},
                    RoadWeaverPlacementAdapterBridge::invokeInitialPlanningCandidateProvider);
            apiType.getMethod("registerInitialPlanningCandidateProvider", providerType)
                    .invoke(null, initialPlanningCandidateProvider);
            LeagueOrchestrator.LOGGER.info("Registered League initial town candidates with RoadWeaver's regional planner.");
        } catch (ReflectiveOperationException exception) {
            LeagueOrchestrator.LOGGER.info("RoadWeaver lacks the initial-candidate API; retaining placement-adapter discovery.");
        }
    }

    public static synchronized boolean isInstalled() {
        return installed;
    }

    private static Object invoke(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "supports" -> arguments != null && arguments.length == 1
                    && arguments[0] instanceof LeagueTownStructurePlacement;
            case "potentialChunks" -> potentialChunks(arguments);
            case "toString" -> "LeagueTownStructurePlacementAdapter";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static List<ChunkPos> potentialChunks(Object[] arguments) {
        if (arguments == null || arguments.length != 6 || !(arguments[0] instanceof LeagueTownStructurePlacement placement)) {
            return List.of();
        }
        long seed = (Long) arguments[1];
        int minChunkX = (Integer) arguments[2];
        int minChunkZ = (Integer) arguments[3];
        int maxChunkX = (Integer) arguments[4];
        int maxChunkZ = (Integer) arguments[5];
        return placement.potentialChunks(seed, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    private static Object invokeInitialPlanningListener(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "onInitialStructuresPlanned" -> {
                if (arguments != null && arguments.length == 2 && arguments[0] instanceof ServerLevel level
                        && arguments[1] instanceof List<?> structures) {
                    recordRoadWeaverCandidates(level, structures);
                }
                yield null;
            }
            case "toString" -> "LeagueInitialPlanningListener";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static Object invokeInitialPlanningBoundsProvider(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "initialBounds" -> initialBounds(arguments);
            case "toString" -> "LeagueInitialPlanningBoundsProvider";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static Object invokeInitialPlanningCandidateProvider(Object proxy, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "initialCandidates" -> initialCandidates(arguments);
            // Kanto's ten deterministic slots are the campaign road graph;
            // generic settlement scans must not add a second, unrelated graph.
            case "exclusive" -> exclusiveFor(arguments);
            case "toString" -> "LeagueInitialPlanningCandidateProvider";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static Optional<Object> initialBounds(Object[] arguments) {
        if (arguments == null || arguments.length != 1 || !(arguments[0] instanceof ServerLevel level)) return Optional.empty();
        WorldGeography.Site site = WorldGeography.siteAt(level, level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ());
        if (site.kind() != WorldGeography.Kind.CONTINENT) return Optional.empty();
        try {
            WorldGeography.Center center = WorldGeography.siteCenter(level, site.cellX(), site.cellZ());
            int envelope = WorldGeography.envelope(level);
            int minX = (int) Math.floor(center.x()) - envelope;
            int minZ = (int) Math.floor(center.z()) - envelope;
            int maxX = (int) Math.ceil(center.x()) + envelope;
            int maxZ = (int) Math.ceil(center.z()) + envelope;
            Class<?> bounds = Class.forName(INITIAL_BOUNDS_CLASS, false, RoadWeaverPlacementAdapterBridge.class.getClassLoader());
            return Optional.of(bounds.getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(minX, minZ, maxX, maxZ));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LeagueOrchestrator.LOGGER.warn("Could not resolve the League initial-planning bounds; RoadWeaver will use its configured radius.", exception);
            return Optional.empty();
        }
    }

    private static List<Object> initialCandidates(Object[] arguments) {
        if (arguments == null || arguments.length != 2 || !(arguments[0] instanceof ServerLevel level)
                || arguments[1] == null) return List.of();
        try {
            if (WorldGeography.isTerraWorld(level)) {
                LeagueTownStructurePlacement.ensureTerraCandidates(level);
            }
            Object bounds = arguments[1];
            int minX = (Integer) bounds.getClass().getMethod("minBlockX").invoke(bounds);
            int minZ = (Integer) bounds.getClass().getMethod("minBlockZ").invoke(bounds);
            int maxX = (Integer) bounds.getClass().getMethod("maxBlockX").invoke(bounds);
            int maxZ = (Integer) bounds.getClass().getMethod("maxBlockZ").invoke(bounds);
            Class<?> infoType = Class.forName(STRUCTURE_INFO_CLASS, false,
                    RoadWeaverPlacementAdapterBridge.class.getClassLoader());
            var constructor = infoType.getConstructor(BlockPos.class, String.class);
            List<SettlementPredictor.RoadWeaverCandidate> supplied = SettlementPredictor
                    .roadWeaverInitialCandidates(level, minX, minZ, maxX, maxZ);
            List<PlannedEndpoint> plannedEndpoints = new ArrayList<>();
            WorldLeaguePlan plan = WorldLeaguePlan.get(level);
            for (SettlementPredictor.RoadWeaverCandidate candidate : supplied) {
                plan.planSettlement(level, candidate.structureId(), candidate.position())
                        .ifPresent(town -> plannedEndpoints.add(new PlannedEndpoint(town, candidate.position())));
            }
            Map<BlockPos, MapIconSpec> mapIcons = resolveMapIconSpecs(level, plannedEndpoints);
            List<Object> candidates = new java.util.ArrayList<>();
            for (SettlementPredictor.RoadWeaverCandidate candidate : supplied) {
                BlockPos position = candidate.position();
                candidates.add(newRoadWeaverStructureInfo(infoType, constructor, position,
                        candidate.structureId().toString(),
                        mapIcons.get(new BlockPos(position.getX(), 0, position.getZ()))));
            }
            // Present the deterministic campaign immediately. Terrain-aware
            // physical routes continue sampling in RoadWeaver's worker.
            publishCampaignPreviewRoutes(level, plannedEndpoints);
            return List.copyOf(candidates);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LeagueOrchestrator.LOGGER.warn("Could not provide deterministic League towns to RoadWeaver's first pass.", exception);
            return List.of();
        }
    }

    private static synchronized boolean exclusiveFor(Object[] arguments) {
        if (arguments == null || arguments.length < 1 || !(arguments[0] instanceof ServerLevel level)) return false;
        boolean generatorIsTerra = WorldGeography.isTerraWorld(level);
        boolean mountClassifiedTerra = LeagueMount.hasTerraMountedState();
        boolean exclusive = generatorIsTerra || mountClassifiedTerra;
        if (!reportedExclusivePolicy) {
            reportedExclusivePolicy = true;
            LeagueOrchestrator.LOGGER.info("RoadWeaver initial candidate policy: exclusive={} generator={} terraGenerator={} terraMount={}",
                    exclusive, level.getChunkSource().getGenerator().getClass().getName(), generatorIsTerra, mountClassifiedTerra);
        }
        return exclusive;
    }

    /**
     * Uses RoadWeaver's already-persisted candidate results. League does not
     * scan structure sets here: it only creates its local ledger entries and
     * synchronously tries the closest candidate as the new-world starter.
     */
    private static void recordRoadWeaverCandidates(ServerLevel level, List<?> structures) {
        int recorded = 0;
        List<PlannedEndpoint> plannedEndpoints = new ArrayList<>();
        for (Object structure : structures) {
            try {
                Object rawId = structure.getClass().getMethod("structureId").invoke(structure);
                Object rawPos = structure.getClass().getMethod("pos").invoke(structure);
                ResourceLocation id = rawId instanceof String value ? ResourceLocation.tryParse(value) : null;
                if (id == null || rawPos == null || !id.getNamespace().equals("bca")) continue;
                BlockPos position = (BlockPos) rawPos;
                Optional<TownAnchor> town = WorldLeaguePlan.get(level).planSettlement(level, id, position);
                if (town.isPresent()) {
                    recorded++;
                    plannedEndpoints.add(new PlannedEndpoint(town.get(), position));
                }
            } catch (ReflectiveOperationException | ClassCastException ignored) {
                // RoadWeaver models are optional at compile time. Skip only an
                // unexpected record rather than disrupting its road pass.
            }
        }
        if (recorded == 0) {
            LeagueOrchestrator.LOGGER.warn("RoadWeaver's initial region contained no usable BCA town candidates; shared spawn remains unchanged.");
            return;
        }
        StarterTownBootstrap.considerPlannedTowns(level);
        // RoadWeaver invokes this listener while its own initial candidate
        // pass is finishing.  Never synchronously load a BCA start here: a
        // chunk-load callback can re-enter C2ME's upgrade path and deadlock
        // the server. considerPlannedTowns queues the same bounded starter
        // through TownStartPrewarmer's ticketed, non-blocking path.
        publishMapIcons(level, plannedEndpoints);
        LeagueOrchestrator.LOGGER.info("Recorded {} RoadWeaver-planned BCA town candidates without forcing chunk generation.", recorded);
    }

    /**
     * Enriches RoadWeaver's persisted endpoint records after its own first
     * candidate pass. The BCA ID stays untouched for placement/planning;
     * only the map presentation is changed. This makes the overview useful
     * before players generate the rest of the campaign world.
     */
    private static void publishMapIcons(ServerLevel level, List<PlannedEndpoint> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) return;
        try {
            WorldLeaguePlan plan = WorldLeaguePlan.get(level);
            List<PlannedEndpoint> ordered = endpoints.stream()
                    .sorted(Comparator.comparingInt((PlannedEndpoint endpoint) -> endpoint.town().center().getX())
                            .thenComparingInt(endpoint -> endpoint.town().center().getZ())
                            .thenComparing(endpoint -> endpoint.town().id()))
                    .toList();
            // A provider can publish its regional candidates before the
            // new-world spawn bootstrap has had a chance to reserve one.
            // The overview must still make the intended entry point obvious;
            // use the nearest candidate as a presentation-only fallback.
            TownAnchor mapStarter = ordered.stream().map(PlannedEndpoint::town)
                    .filter(plan::isStarterTown)
                    .findFirst()
                    .orElseGet(() -> ordered.stream()
                            .min(Comparator.comparingDouble((PlannedEndpoint endpoint) ->
                                    endpoint.position().distSqr(level.getSharedSpawnPos()))
                                    .thenComparing(endpoint -> endpoint.town().id()))
                            .orElseThrow()
                            .town());
            Map<String, GymArchetype> gyms = new HashMap<>();
            List<PlannedEndpoint> gymAllocationOrder = ordered.stream()
                    .filter(endpoint -> !endpoint.town().equals(mapStarter))
                    .sorted(Comparator.comparingInt((PlannedEndpoint endpoint) ->
                                    plan.gymTownPriority(level, endpoint.town())).reversed()
                            .thenComparing(endpoint -> endpoint.town().id()))
                    .toList();
            for (PlannedEndpoint endpoint : gymAllocationOrder) {
                TownAnchor town = endpoint.town();
                if (town.equals(mapStarter)) continue;
                Optional<CampaignDefinition> campaign = plan.campaignFor(level, town.center());
                if (campaign.isEmpty() || campaign.get().gymPool().isBlank()) continue;
                plan.gymForTown(level, town, campaign.get().gymPool())
                        .ifPresent(gym -> gyms.put(town.id(), gym));
            }

            TownAnchor eliteFour = selectKantoEliteFour(level, plan, ordered, mapStarter);
            for (PlannedEndpoint endpoint : ordered) {
                TownAnchor town = endpoint.town();
                MapIconSpec spec = town.equals(mapStarter)
                        ? new MapIconSpec("STARTER", "", 0xFFDE4A42, town.name() + " · starting town")
                        : town.equals(eliteFour)
                        ? new MapIconSpec("ELITE_FOUR", "M", 0xFF7655B8, "Elite Four · " + town.name())
                        : gyms.containsKey(town.id())
                        ? gymIcon(town, gyms.get(town.id()))
                        : extraSettlementIcon(town);
                setRoadWeaverMapIcon(level, endpoint.position(), spec);
            }
            publishCampaignPreviewRoutes(level, ordered);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LeagueOrchestrator.LOGGER.warn("Could not publish League map icons to RoadWeaver; its normal structure markers remain available.", exception);
        }
    }

    /** Builds the presentation without requiring RoadWeaver persistence to be complete. */
    private static Map<BlockPos, MapIconSpec> resolveMapIconSpecs(ServerLevel level,
                                                                    List<PlannedEndpoint> endpoints) {
        Map<BlockPos, MapIconSpec> specs = new HashMap<>();
        if (endpoints == null || endpoints.isEmpty()) return specs;
        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        List<PlannedEndpoint> ordered = endpoints.stream()
                .sorted(Comparator.comparingInt((PlannedEndpoint endpoint) -> endpoint.town().center().getX())
                        .thenComparingInt(endpoint -> endpoint.town().center().getZ())
                        .thenComparing(endpoint -> endpoint.town().id()))
                .toList();
        TownAnchor mapStarter = ordered.stream().map(PlannedEndpoint::town)
                .filter(plan::isStarterTown)
                .findFirst()
                .orElseGet(() -> ordered.stream()
                        .min(Comparator.comparingDouble((PlannedEndpoint endpoint) ->
                                endpoint.position().distSqr(level.getSharedSpawnPos()))
                                .thenComparing(endpoint -> endpoint.town().id()))
                        .orElseThrow()
                        .town());
        Map<String, GymArchetype> gyms = new HashMap<>();
        List<PlannedEndpoint> gymAllocationOrder = ordered.stream()
                .filter(endpoint -> !endpoint.town().equals(mapStarter))
                .sorted(Comparator.comparingInt((PlannedEndpoint endpoint) ->
                                plan.gymTownPriority(level, endpoint.town())).reversed()
                        .thenComparing(endpoint -> endpoint.town().id()))
                .toList();
        for (PlannedEndpoint endpoint : gymAllocationOrder) {
            TownAnchor town = endpoint.town();
            Optional<CampaignDefinition> campaign = plan.campaignFor(level, town.center());
            if (campaign.isPresent() && !campaign.get().gymPool().isBlank()) {
                plan.gymForTown(level, town, campaign.get().gymPool()).ifPresent(gym -> gyms.put(town.id(), gym));
            }
        }
        TownAnchor eliteFour = selectKantoEliteFour(level, plan, ordered, mapStarter);
        for (PlannedEndpoint endpoint : ordered) {
            TownAnchor town = endpoint.town();
            MapIconSpec spec = town.equals(mapStarter)
                    ? new MapIconSpec("STARTER", "", 0xFFDE4A42, town.name() + " · starting town")
                    : town.equals(eliteFour)
                    ? new MapIconSpec("ELITE_FOUR", "M", 0xFF7655B8, "Elite Four · " + town.name())
                    : gyms.containsKey(town.id())
                    ? gymIcon(town, gyms.get(town.id()))
                    : extraSettlementIcon(town);
            specs.put(new BlockPos(endpoint.position().getX(), 0, endpoint.position().getZ()), spec);
        }
        return specs;
    }

    private static TownAnchor selectKantoEliteFour(ServerLevel level, WorldLeaguePlan plan,
                                                    List<PlannedEndpoint> endpoints, TownAnchor starter) {
        if (endpoints.isEmpty() || plan.campaignIdFor(level, endpoints.getFirst().town().center())
                .filter(id -> id.equals(ResourceLocation.fromNamespaceAndPath("league_orchestrator", "kanto_classic"))).isEmpty()) {
            return null;
        }
        return endpoints.stream().map(PlannedEndpoint::town)
                .filter(town -> TownTier.fromSettlementId(town.id()) == TownTier.LARGE)
                .filter(town -> !town.equals(starter))
                .max(Comparator.comparingDouble((TownAnchor town) -> town.center().distSqr(starter.center()))
                        .thenComparing(TownAnchor::id))
                .orElse(null);
    }

    /**
     * Publishes an itinerary layer immediately from the provider's predicted
     * candidate roster. These are map-only links: RoadWeaver remains the
     * authority for terrain-aware physical roads and may later replace or
     * overdraw them with its planned polylines.
     */
    private static void publishCampaignPreviewRoutes(ServerLevel level, List<PlannedEndpoint> endpoints) {
        Map<String, PlannedEndpoint> byKey = new HashMap<>();
        Map<String, BlockPos> positions = new HashMap<>();
        for (int index = 0; index < endpoints.size(); index++) {
            PlannedEndpoint endpoint = endpoints.get(index);
            String key = index + "/" + endpoint.town().id();
            byKey.put(key, endpoint);
            positions.put(key, endpoint.position());
        }
        for (CampaignRoute route : RoadGraphPlanner.minimumSpanningTree(positions)) {
            PlannedEndpoint from = byKey.get(route.fromTownKey());
            PlannedEndpoint to = byKey.get(route.toTownKey());
            if (from == null || to == null) continue;
            String routeClass = previewRouteClass(from.town(), to.town());
            RoadWeaverBridge.publishPreviewMapRoute(level, from.position(), to.position(), routeClass,
                    "preview:" + route.id());
        }
    }

    private static String previewRouteClass(TownAnchor first, TownAnchor second) {
        TownTier firstTier = TownTier.fromSettlementId(first.id());
        TownTier secondTier = TownTier.fromSettlementId(second.id());
        if (firstTier == TownTier.LARGE || secondTier == TownTier.LARGE) return "STREET";
        if (firstTier == TownTier.MID || secondTier == TownTier.MID) return "ROAD";
        return "TRAIL";
    }

    private static MapIconSpec extraSettlementIcon(TownAnchor town) {
        String tier = switch (TownTier.fromSettlementId(town.id())) {
            case SMALL -> "S";
            case MID -> "M";
            case LARGE -> "L";
            case UNKNOWN -> "";
        };
        return new MapIconSpec("OUTPOST", tier, 0xFF7E8B96,
                "Outlying settlement · " + town.name());
    }

    private static MapIconSpec gymIcon(TownAnchor town, GymArchetype gym) {
        if (gym == null) {
            String marker = switch (TownTier.fromSettlementId(town.id())) {
                case SMALL -> "S";
                case MID -> "M";
                case LARGE -> "L";
                case UNKNOWN -> "";
            };
            return new MapIconSpec(TownTier.fromSettlementId(town.id()) == TownTier.UNKNOWN ? "OUTPOST" : "TOWN",
                    marker, townColor(marker), town.name());
        }
        String type = gym.id().getPath().replaceFirst("^kanto_", "");
        return new MapIconSpec("GYM", type, typeColor(type), town.name() + " · " + title(type) + " Gym");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setRoadWeaverMapIcon(ServerLevel level, BlockPos position, MapIconSpec spec)
            throws ReflectiveOperationException {
        ClassLoader loader = RoadWeaverPlacementAdapterBridge.class.getClassLoader();
        Class<?> iconType = Class.forName(MAP_LOCATION_ICON_CLASS, false, loader);
        Object icon = newRoadWeaverMapIcon(spec, iconType, loader);
        Class<?> api = Class.forName(API_CLASS, false, loader);
        api.getMethod("setStructureEndpointIcon", ServerLevel.class, BlockPos.class, iconType)
                .invoke(null, level, new BlockPos(position.getX(), 0, position.getZ()), icon);
    }

    private static Object newRoadWeaverStructureInfo(Class<?> infoType,
                                                      java.lang.reflect.Constructor<?> legacyConstructor,
                                                      BlockPos position,
                                                      String structureId,
                                                      MapIconSpec spec) throws ReflectiveOperationException {
        if (spec == null) return legacyConstructor.newInstance(position, structureId);
        try {
            ClassLoader loader = RoadWeaverPlacementAdapterBridge.class.getClassLoader();
            Class<?> iconType = Class.forName(MAP_LOCATION_ICON_CLASS, false, loader);
            Object icon = newRoadWeaverMapIcon(spec, iconType, loader);
            return infoType.getConstructor(BlockPos.class, String.class, iconType)
                    .newInstance(position, structureId, icon);
        } catch (ReflectiveOperationException unsupportedIconApi) {
            return legacyConstructor.newInstance(position, structureId);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object newRoadWeaverMapIcon(MapIconSpec spec, Class<?> iconType, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> kindType = Class.forName(MAP_LOCATION_ICON_KIND_CLASS, false, loader);
        Object kind = Enum.valueOf((Class<? extends Enum>) kindType.asSubclass(Enum.class), spec.kind());
        return iconType.getConstructor(kindType, String.class, int.class, String.class)
                .newInstance(kind, spec.marker(), spec.color(), spec.label());
    }

    private static int typeColor(String type) {
        return switch (type) {
            case "fire" -> 0xFFF0523D;
            case "water" -> 0xFF4D90D5;
            case "electric" -> 0xFFF8D030;
            case "grass" -> 0xFF78C850;
            case "poison" -> 0xFFA040A0;
            case "psychic" -> 0xFFF85888;
            case "ground" -> 0xFFE0C068;
            case "rock" -> 0xFFB8A038;
            default -> 0xFF5FAE63;
        };
    }

    private static int townColor(String marker) {
        return switch (marker) {
            case "S" -> 0xFF5FAE63;
            case "M" -> 0xFF4D90D5;
            case "L" -> 0xFF7655B8;
            default -> 0xFFB28A52;
        };
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "League";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private record PlannedEndpoint(TownAnchor town, BlockPos position) {}
    private record MapIconSpec(String kind, String marker, int color, String label) {}
}
