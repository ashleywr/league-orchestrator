package dev.leagueorchestrator.world;

import dev.leagueorchestrator.LeagueOrchestrator;
import dev.leagueorchestrator.data.GymArchetype;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.leagueorchestrator.provider.ProviderIntegrationCoordinator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * RCT adapter for stationary gym leaders. Every RCT call is routed through
 * {@link ProviderIntegrationCoordinator}; this class never loads or generates
 * a chunk merely to place a leader.
 */
public final class RctGymLeaderBridge {
    private static final double ACTIVATION_DISTANCE = 192.0;
    private static volatile RctFacade facade;

    private RctGymLeaderBridge() {}

    static void enqueue(ServerLevel level, TownAnchor town, GymArchetype gym) {
        if (gym.trainerId().isBlank()) {
            WorldLeaguePlan.get(level).markGymLeaderUnassigned(town);
            return;
        }
        if (town.gymLeaderPosition() == null
                || town.gymLeaderState() == GymLeaderState.UNASSIGNED
                || town.gymLeaderState() == GymLeaderState.DEGRADED) return;
        Intent intent = new Intent(level.dimension().location(), town.siteX(), town.siteZ(), town.id(), town.center(), gym.id());
        if (!ProviderIntegrationCoordinator.submit(new RctGymLeaderAction(intent))) {
            // The desired leader remains PENDING/ACTIVE in SavedData. A later
            // chunk-load or reconciliation pass can submit it again.
            LeagueOrchestrator.LOGGER.debug("RCT leader request {} deferred because the integration queue is full.", intent.key());
        }
    }

    public static void tick(MinecraftServer server) {
        ProviderIntegrationCoordinator.tick(server);
    }

    /**
     * Re-reads every RCT trainer resource, including any progression file a
     * generator wrote into this world's own datapacks since the last load.
     * RCT reconciles already-spawned leaders against the refreshed data
     * itself; League does not need to re-anchor anything afterward. Callers
     * must already be on the server thread (e.g. a command execution).
     */
    public static boolean reloadTrainerData(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("reloadTrainerData must run on the server thread");
        }
        if (!FabricLoader.getInstance().isModLoaded("rctmod")) {
            LeagueOrchestrator.LOGGER.warn("Cannot reload RCT trainer data: rctmod is not installed.");
            return false;
        }
        try {
            facade().reloadTrainers();
            LeagueOrchestrator.LOGGER.info("Reloaded RCT trainer data.");
            return true;
        } catch (ReflectiveOperationException exception) {
            LeagueOrchestrator.LOGGER.warn("Could not reload RCT trainer data.", exception);
            return false;
        }
    }

    public static void stop() {
        facade = null;
        ProviderIntegrationCoordinator.stop();
    }

    private static final class RctGymLeaderAction implements ProviderIntegrationCoordinator.ServerAction {
        private final Intent intent;

        private RctGymLeaderAction(Intent intent) {
            this.intent = intent;
        }

        @Override
        public String key() {
            return intent.key();
        }

        @Override
        public String providerId() {
            return "rctmod";
        }

        @Override
        public String operationId() {
            return "gym_leader_spawn";
        }

        @Override
        public ProviderIntegrationCoordinator.ActionResult execute(MinecraftServer server) throws Exception {
            ServerLevel level = LeagueMount.level(server);
            if (!level.dimension().location().equals(intent.dimension())) return ProviderIntegrationCoordinator.ActionResult.DROP;

            GymArchetype gym = LeagueDataReloadListener.current().gymArchetypes().get(intent.gymId());
            WorldLeaguePlan plan = WorldLeaguePlan.get(level);
            TownAnchor town = plan.townAt(level, intent.townCenter(), intent.townId()).orElse(null);
            if (gym == null || town == null || town.gymLeaderPosition() == null) return ProviderIntegrationCoordinator.ActionResult.DROP;
            if (town.gymLeaderState() == GymLeaderState.DEGRADED || town.gymLeaderState() == GymLeaderState.UNASSIGNED) {
                return ProviderIntegrationCoordinator.ActionResult.DROP;
            }

            // getChunkNow observes loaded state only. Calling getChunk here
            // would synchronously generate terrain on the server tick.
            if (level.getChunkSource().getChunkNow(town.gymLeaderPosition().getX() >> 4,
                    town.gymLeaderPosition().getZ() >> 4) == null) {
                return ProviderIntegrationCoordinator.ActionResult.RETRY_LONG;
            }
            if (!FabricLoader.getInstance().isModLoaded("rctmod") || !FabricLoader.getInstance().isModLoaded("rctapi")) {
                return degrade(plan, town, "RCT/RCT API is not installed");
            }

            RctFacade api = facade();
            if (town.gymLeaderState() == GymLeaderState.ACTIVE) {
                if (town.gymLeaderUuid() != null && level.getEntity(town.gymLeaderUuid()) != null) {
                    return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
                }
                Entity existing = api.findExactLeader(gym.trainerId(), town.gymLeaderPosition());
                if (existing != null) {
                    plan.markGymLeaderSpawned(town, existing.getUUID());
                    return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
                }
                // This anchor's chunk is loaded and no exact RCT home/trainer
                // exists. Only now is replacement a proven-safe transition.
                plan.markGymLeaderPending(town);
                town = plan.townAt(level, intent.townCenter(), intent.townId()).orElse(town);
            }

            if (town.gymLeaderState() == GymLeaderState.VERIFYING) {
                Entity existing = api.findExactLeader(gym.trainerId(), town.gymLeaderPosition());
                if (existing != null) {
                    plan.markGymLeaderSpawned(town, existing.getUUID());
                    return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
                }
                return degrade(plan, town, "Previous RCT spawn outcome is ambiguous; automatic replay is disabled");
            }
            if (town.gymLeaderState() != GymLeaderState.PENDING) return ProviderIntegrationCoordinator.ActionResult.DROP;

            // A manual recovery or a delayed queue entry can meet a leader
            // which RCT already registered. Reconcile before entering a second
            // spawn call even while the durable state says PENDING.
            Entity existing = api.findExactLeader(gym.trainerId(), town.gymLeaderPosition());
            if (existing != null) {
                plan.markGymLeaderSpawned(town, existing.getUUID());
                return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
            }

            ServerPlayer player = nearestPlayer(level, town.gymLeaderPosition());
            if (player == null) return ProviderIntegrationCoordinator.ActionResult.RETRY_SHORT;
            String seriesId = plan.rctSeriesIdFor(level, town.center()).orElse("");
            if (seriesId.isBlank()) return degrade(plan, town, "Campaign has no RCT series for this gym");
            if (!api.hasSeries(seriesId)) return ProviderIntegrationCoordinator.ActionResult.RETRY_LONG;
            if (!api.isValidTrainer(gym.trainerId())) return degrade(plan, town, "Unknown RCT trainer: " + gym.trainerId());
            if (!api.belongsToSeries(gym.trainerId(), seriesId)) {
                return degrade(plan, town, "Trainer is not in RCT series: " + seriesId);
            }

            // Persist this transition before entering RCT. If the provider or
            // server fails after this point, restart logic reconciles exactly
            // once and otherwise leaves the anchor degraded rather than cloning
            // a potentially existing trainer.
            if (!plan.markGymLeaderVerifying(town)) return ProviderIntegrationCoordinator.ActionResult.RETRY_SHORT;
            TownAnchor verifying = plan.townAt(level, intent.townCenter(), intent.townId()).orElse(town);
            Entity spawned = api.spawn(player, gym.trainerId(), verifying.gymLeaderPosition());
            if (spawned == null) {
                Entity spawnedExisting = api.findExactLeader(gym.trainerId(), verifying.gymLeaderPosition());
                if (spawnedExisting != null) {
                    plan.markGymLeaderSpawned(verifying, spawnedExisting.getUUID());
                    return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
                }
                return degrade(plan, verifying, "RCT spawn returned no verifiable leader");
            }
            api.makePersistent(spawned);
            plan.markGymLeaderSpawned(verifying, spawned.getUUID());
            LeagueOrchestrator.LOGGER.info("Anchored RCT gym leader '{}' for {} at {} in series '{}'.",
                    gym.trainerId(), gym.id(), verifying.gymLeaderPosition().toShortString(), seriesId);
            return ProviderIntegrationCoordinator.ActionResult.COMPLETE;
        }

        @Override
        public void onFailure(MinecraftServer server, Throwable throwable) {
            ServerLevel level = LeagueMount.level(server);
            if (!level.dimension().location().equals(intent.dimension())) return;
            WorldLeaguePlan plan = WorldLeaguePlan.get(level);
            plan.townAt(level, intent.townCenter(), intent.townId())
                    .ifPresent(town -> plan.markGymLeaderDegraded(town,
                            "Unexpected RCT error; inspect server log before repair"));
        }
    }

    private static ProviderIntegrationCoordinator.ActionResult degrade(WorldLeaguePlan plan, TownAnchor town, String reason) {
        plan.markGymLeaderDegraded(town, reason);
        LeagueOrchestrator.LOGGER.warn("RCT gym leader at {} is degraded: {}", town.gymLeaderPosition().toShortString(), reason);
        return ProviderIntegrationCoordinator.ActionResult.DEGRADED;
    }

    private static RctFacade facade() throws ReflectiveOperationException {
        RctFacade current = facade;
        if (current != null) return current;
        synchronized (RctGymLeaderBridge.class) {
            if (facade == null) facade = RctFacade.resolve();
            return facade;
        }
    }

    private static ServerPlayer nearestPlayer(ServerLevel level, BlockPos position) {
        double maximumDistanceSquared = ACTIVATION_DISTANCE * ACTIVATION_DISTANCE;
        return level.players().stream()
                .filter(player -> player.distanceToSqr(position.getCenter()) <= maximumDistanceSquared)
                .min(java.util.Comparator.comparingDouble(player -> player.distanceToSqr(position.getCenter())))
                .orElse(null);
    }

    /** Cached reflection over RCT's documented public server API. */
    private static final class RctFacade {
        private final Object rct;
        private final Method trainerManager;
        private final Method seriesManager;
        private final Method trainerSpawner;
        private final Method seriesIds;
        private final Method validTrainer;
        private final Method trainerData;
        private final Method trainerInSeries;
        private final Method spawn;
        private final Method spawns;
        private final Method trainerId;
        private final Method homePos;
        private final Method persistent;
        private final Method loadTrainers;

        private RctFacade(Object rct, Method trainerManager, Method seriesManager, Method trainerSpawner, Method seriesIds,
                          Method validTrainer, Method trainerData, Method trainerInSeries, Method spawn, Method spawns,
                          Method trainerId, Method homePos, Method persistent, Method loadTrainers) {
            this.rct = rct;
            this.trainerManager = trainerManager;
            this.seriesManager = seriesManager;
            this.trainerSpawner = trainerSpawner;
            this.seriesIds = seriesIds;
            this.validTrainer = validTrainer;
            this.trainerData = trainerData;
            this.trainerInSeries = trainerInSeries;
            this.spawn = spawn;
            this.spawns = spawns;
            this.trainerId = trainerId;
            this.homePos = homePos;
            this.persistent = persistent;
            this.loadTrainers = loadTrainers;
        }

        static RctFacade resolve() throws ReflectiveOperationException {
            Class<?> rctClass = Class.forName("com.gitlab.srcmc.rctmod.api.RCTMod");
            Object rct = rctClass.getMethod("getInstance").invoke(null);
            Object trainerManager = rctClass.getMethod("getTrainerManager").invoke(rct);
            Object seriesManager = rctClass.getMethod("getSeriesManager").invoke(rct);
            Object spawner = rctClass.getMethod("getTrainerSpawner").invoke(rct);
            Class<?> trainerMob = Class.forName("com.gitlab.srcmc.rctmod.world.entities.TrainerMob");
            return new RctFacade(rct, rctClass.getMethod("getTrainerManager"), rctClass.getMethod("getSeriesManager"),
                    rctClass.getMethod("getTrainerSpawner"), seriesManager.getClass().getMethod("getSeriesIds"),
                    trainerManager.getClass().getMethod("isValidId", String.class), trainerManager.getClass().getMethod("getData", String.class),
                    Class.forName("com.gitlab.srcmc.rctmod.api.data.pack.TrainerMobData").getMethod("isOfSeries", String.class),
                    spawner.getClass().getMethod("attemptSpawnFor", ServerPlayer.class, String.class, BlockPos.class,
                            boolean.class, boolean.class, boolean.class, double.class, double.class),
                    spawner.getClass().getMethod("getSpawns"), trainerMob.getMethod("getTrainerId"), trainerMob.getMethod("getHomePos"),
                    trainerMob.getMethod("setPersistent", boolean.class), trainerManager.getClass().getMethod("loadTrainers"));
        }

        void reloadTrainers() throws ReflectiveOperationException {
            loadTrainers.invoke(trainerManager.invoke(rct));
        }

        @SuppressWarnings("unchecked")
        boolean hasSeries(String id) throws ReflectiveOperationException {
            return ((Set<String>) seriesIds.invoke(seriesManager.invoke(rct))).contains(id);
        }

        boolean isValidTrainer(String id) throws ReflectiveOperationException {
            return (boolean) validTrainer.invoke(trainerManager.invoke(rct), id);
        }

        boolean belongsToSeries(String id, String series) throws ReflectiveOperationException {
            Object data = trainerData.invoke(trainerManager.invoke(rct), id);
            return data != null && (boolean) trainerInSeries.invoke(data, series);
        }

        Entity spawn(ServerPlayer player, String trainer, BlockPos home) throws ReflectiveOperationException {
            Object value = spawn.invoke(trainerSpawner.invoke(rct), player, trainer, home, true, true, true, 0.0, 0.0);
            return value instanceof Entity entity ? entity : null;
        }

        @SuppressWarnings("unchecked")
        Entity findExactLeader(String expectedTrainer, BlockPos expectedHome) throws ReflectiveOperationException {
            for (Object candidate : (Set<Object>) spawns.invoke(trainerSpawner.invoke(rct))) {
                if (!(candidate instanceof Entity entity)) continue;
                if (expectedTrainer.equals(trainerId.invoke(candidate)) && expectedHome.equals(homePos.invoke(candidate))) return entity;
            }
            return null;
        }

        void makePersistent(Entity entity) throws ReflectiveOperationException {
            persistent.invoke(entity, true);
        }
    }

    private record Intent(ResourceLocation dimension, int siteX, int siteZ, String townId,
                          BlockPos townCenter, ResourceLocation gymId) {
        private String key() {
            return dimension + "|" + siteX + "," + siteZ + "|" + townId;
        }
    }
}
