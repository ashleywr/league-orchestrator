package dev.leagueorchestrator.world;

import dev.leagueorchestrator.LeagueOrchestrator;
import dev.regionengine.config.AtlasConfig;
import dev.regionengine.policy.RegionPolicyService;
import dev.regionengine.world.atlas.AtlasMath;
import dev.regionengine.world.atlas.AtlasRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
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
    private static final String INITIAL_BOUNDS_CLASS = "net.shiroha233.roadweaver.api.InitialPlanningBounds";
    private static boolean installed;
    private static boolean unavailable;
    private static Object adapter;
    private static Object initialPlanningListener;
    private static Object initialPlanningBoundsProvider;

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
            installed = true;
            LeagueOrchestrator.LOGGER.info("Registered League planned-town placement with RoadWeaver's regional planner.");
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            LeagueOrchestrator.LOGGER.info("RoadWeaver lacks the League placement-adapter API; retaining the realized-endpoint fallback.");
        }
    }

    /** Newer RoadWeaver builds can make the first pass match one Atlas continent exactly. */
    private static void installInitialBoundsProvider(ClassLoader loader, Class<?> apiType) {
        try {
            Class<?> providerType = Class.forName(INITIAL_BOUNDS_PROVIDER_CLASS, false, loader);
            initialPlanningBoundsProvider = Proxy.newProxyInstance(loader, new Class<?>[]{providerType},
                    RoadWeaverPlacementAdapterBridge::invokeInitialPlanningBoundsProvider);
            apiType.getMethod("registerInitialPlanningBoundsProvider", providerType).invoke(null, initialPlanningBoundsProvider);
            LeagueOrchestrator.LOGGER.info("Registered League Atlas bounds with RoadWeaver's initial planner.");
        } catch (ReflectiveOperationException exception) {
            LeagueOrchestrator.LOGGER.info("RoadWeaver lacks the initial-bounds API; using its configured initial radius.");
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
            case "toString" -> "LeagueAtlasInitialPlanningBoundsProvider";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
            default -> throw new UnsupportedOperationException(method.toString());
        };
    }

    private static Optional<Object> initialBounds(Object[] arguments) {
        if (arguments == null || arguments.length != 1 || !(arguments[0] instanceof ServerLevel level)) return Optional.empty();
        AtlasRegion site = RegionPolicyService.atlasRegionAt(level.getSeed(), level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ());
        if (site.kind() != AtlasRegion.Kind.CONTINENT) return Optional.empty();
        try {
            AtlasConfig config = AtlasConfig.get();
            AtlasMath.SiteCenter center = AtlasMath.siteCenter(level.getSeed(), site.cellX(), site.cellZ());
            int envelope = config.maxRadius + (int) Math.ceil(config.maxCoastProtrusion);
            int minX = (int) Math.floor(center.x()) - envelope;
            int minZ = (int) Math.floor(center.z()) - envelope;
            int maxX = (int) Math.ceil(center.x()) + envelope;
            int maxZ = (int) Math.ceil(center.z()) + envelope;
            Class<?> bounds = Class.forName(INITIAL_BOUNDS_CLASS, false, RoadWeaverPlacementAdapterBridge.class.getClassLoader());
            return Optional.of(bounds.getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(minX, minZ, maxX, maxZ));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LeagueOrchestrator.LOGGER.warn("Could not resolve the Atlas initial-planning bounds; RoadWeaver will use its configured radius.", exception);
            return Optional.empty();
        }
    }

    /**
     * Uses RoadWeaver's already-persisted candidate results. League does not
     * scan structure sets here: it only creates its local ledger entries and
     * synchronously tries the closest candidate as the new-world starter.
     */
    private static void recordRoadWeaverCandidates(ServerLevel level, List<?> structures) {
        int recorded = 0;
        for (Object structure : structures) {
            try {
                Object rawId = structure.getClass().getMethod("structureId").invoke(structure);
                Object rawPos = structure.getClass().getMethod("pos").invoke(structure);
                ResourceLocation id = rawId instanceof String value ? ResourceLocation.tryParse(value) : null;
                if (id == null || rawPos == null || !id.getNamespace().equals("bca")) continue;
                BlockPos position = (BlockPos) rawPos;
                if (WorldLeaguePlan.get(level).planSettlement(level, id, position).isPresent()) recorded++;
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
        StarterTownBootstrap.materializeBeforeFirstJoin(level);
        LeagueOrchestrator.LOGGER.info("Recorded {} RoadWeaver-planned BCA town candidates before initial road generation.", recorded);
    }
}
