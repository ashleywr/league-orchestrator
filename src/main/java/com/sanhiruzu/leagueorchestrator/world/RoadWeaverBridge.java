package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import com.sanhiruzu.leagueorchestrator.data.GymArchetype;
import com.sanhiruzu.leagueorchestrator.provider.ProviderIntegrationCoordinator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Narrow, optional RoadWeaver adapter. League owns the deterministic graph;
 * RoadWeaver receives only its already-selected endpoints and edges through
 * the shared provider coordinator.
 */
public final class RoadWeaverBridge {
    private static final String API_CLASS = "net.shiroha233.roadweaver.api.RoadNetworkApi";
    private static final Set<String> PUBLISHED_ENDPOINTS = new HashSet<>();
    private static final Set<String> PUBLISHED_ROUTES = new HashSet<>();

    private static ApiMethods api;
    private static boolean unavailable;

    private RoadWeaverBridge() {}

    /** Publishes a realized town's outer endpoint. */
    public static void publishTown(ServerLevel level, TownAnchor town) {
        if (RoadWeaverPlacementAdapterBridge.isInstalled()) return;
        submitEndpoint(level, townEndpoint(town), townLabel(town));
    }

    /** Queues one explicit campaign route. RoadWeaver's false argument preserves its asynchronous renderer. */
    public static void connectCampaignRoute(ServerLevel level, String routeId, TownAnchor from, TownAnchor to) {
        if (RoadWeaverPlacementAdapterBridge.isInstalled()) return;
        if (!isMounted(level)) return;
        BlockPos fromPosition = townEndpoint(from);
        BlockPos toPosition = townEndpoint(to);
        submitEndpoint(level, fromPosition, townLabel(from));
        submitEndpoint(level, toPosition, townLabel(to));
        submitRoute(level, fromPosition, toPosition, "campaign:" + routeId);
    }

    /** Queues the controlled, short road spur from a known town to its placed gym. */
    public static void connectPlacedGym(ServerLevel level, TownAnchor town, BlockPos gymPosition, GymArchetype gym) {
        if (!isMounted(level)) return;
        BlockPos townPosition = townEndpoint(town);
        BlockPos gymEndpoint = horizontal(gymPosition);
        submitEndpoint(level, townPosition, townLabel(town));
        submitEndpoint(level, gymEndpoint, "league_orchestrator:gym/" + town.id() + "/" + gym.id().getPath());
        submitRoute(level, townPosition, gymEndpoint, "gym:" + town.id() + "/" + gym.id());
    }

    /** Clears only process-local cache when an integrated server stops. */
    public static synchronized void stop() {
        PUBLISHED_ENDPOINTS.clear();
        PUBLISHED_ROUTES.clear();
        api = null;
        unavailable = false;
    }

    private static void submitEndpoint(ServerLevel level, BlockPos position, String label) {
        if (!isMounted(level) || isUnavailable()) return;
        EndpointIntent intent = new EndpointIntent(level.dimension().location(), position, label);
        if (!isEndpointPublished(intent.key()) && !ProviderIntegrationCoordinator.submit(new EndpointAction(intent))) {
            LeagueOrchestrator.LOGGER.debug("RoadWeaver endpoint {} deferred because the integration queue is full.", intent.key());
        }
    }

    private static void submitRoute(ServerLevel level, BlockPos from, BlockPos to, String routeId) {
        if (!isMounted(level) || isUnavailable()) return;
        RouteIntent intent = new RouteIntent(level.dimension().location(), from, to, routeId);
        if (!isRoutePublished(intent.key()) && !ProviderIntegrationCoordinator.submit(new RouteAction(intent))) {
            LeagueOrchestrator.LOGGER.debug("RoadWeaver route {} deferred because the integration queue is full.", intent.key());
        }
    }

    private static final class EndpointAction implements ProviderIntegrationCoordinator.ServerAction {
        private final EndpointIntent intent;

        private EndpointAction(EndpointIntent intent) {
            this.intent = intent;
        }

        @Override public String key() { return intent.key(); }
        @Override public String providerId() { return "roadweaver"; }
        @Override public String operationId() { return "register_endpoint"; }

        @Override
        public ProviderIntegrationCoordinator.ActionResult execute(MinecraftServer server) throws Exception {
            if (!isMounted(server, intent.dimension()) || isUnavailable() || isEndpointPublished(intent.key())) {
                return ProviderIntegrationCoordinator.ActionResult.DROP;
            }
            api().register().invoke(null, LeagueMount.level(server), intent.position(), intent.label(), false);
            markEndpointPublished(intent.key());
            return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
        }

        @Override
        public void onFailure(MinecraftServer server, Throwable throwable) {
            disable(throwable);
        }
    }

    private static final class RouteAction implements ProviderIntegrationCoordinator.ServerAction {
        private final RouteIntent intent;

        private RouteAction(RouteIntent intent) {
            this.intent = intent;
        }

        @Override public String key() { return intent.key(); }
        @Override public String providerId() { return "roadweaver"; }
        @Override public String operationId() { return "ensure_connection"; }

        @Override
        public ProviderIntegrationCoordinator.ActionResult execute(MinecraftServer server) throws Exception {
            if (!isMounted(server, intent.dimension()) || isUnavailable() || isRoutePublished(intent.key())) {
                return ProviderIntegrationCoordinator.ActionResult.DROP;
            }
            // Do not change this to true: true invokes RoadWeaver generation
            // immediately, which would create a worldgen stall.
            api().connect().invoke(null, LeagueMount.level(server), intent.from(), intent.to(), false);
            markRoutePublished(intent.key());
            LeagueOrchestrator.LOGGER.info("Queued RoadWeaver route {} from {} to {}.",
                    intent.routeId(), intent.from().toShortString(), intent.to().toShortString());
            return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
        }

        @Override
        public void onFailure(MinecraftServer server, Throwable throwable) {
            disable(throwable);
        }
    }

    private static synchronized ApiMethods api() throws ReflectiveOperationException {
        if (api != null) return api;
        Class<?> type = Class.forName(API_CLASS);
        api = new ApiMethods(
                type.getMethod("registerStructureEndpoint", ServerLevel.class, BlockPos.class, String.class, boolean.class),
                type.getMethod("ensureConnection", ServerLevel.class, BlockPos.class, BlockPos.class, boolean.class)
        );
        return api;
    }

    private static synchronized boolean isUnavailable() { return unavailable; }
    private static synchronized boolean isEndpointPublished(String key) { return PUBLISHED_ENDPOINTS.contains(key); }
    private static synchronized boolean isRoutePublished(String key) { return PUBLISHED_ROUTES.contains(key); }
    private static synchronized void markEndpointPublished(String key) { PUBLISHED_ENDPOINTS.add(key); }
    private static synchronized void markRoutePublished(String key) { PUBLISHED_ROUTES.add(key); }

    private static synchronized void disable(Throwable exception) {
        unavailable = true;
        LeagueOrchestrator.LOGGER.warn("RoadWeaver API failed; League keeps its campaign ledger and stops automatic RoadWeaver calls until restart.", exception);
    }

    private static boolean isMounted(ServerLevel level) {
        return LeagueMount.isMounted(level);
    }

    private static boolean isMounted(MinecraftServer server, ResourceLocation dimension) {
        return LeagueMount.isMounted(server, dimension);
    }

    private static BlockPos townEndpoint(TownAnchor town) { return horizontal(town.routeEndpoint()); }
    private static String townLabel(TownAnchor town) {
        return "league_orchestrator:town/" + town.id();
    }
    private static BlockPos horizontal(BlockPos position) { return new BlockPos(position.getX(), 0, position.getZ()); }

    private record EndpointIntent(ResourceLocation dimension, BlockPos position, String label) {
        private String key() { return "roadweaver|endpoint|" + dimension + "|" + position.asLong(); }
    }

    private record RouteIntent(ResourceLocation dimension, BlockPos from, BlockPos to, String routeId) {
        private String key() { return "roadweaver|route|" + dimension + "|" + routeId; }
    }

    private record ApiMethods(Method register, Method connect) {}
}
