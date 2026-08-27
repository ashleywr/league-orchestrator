package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Converts the first realized League town in a new world into the shared
 * spawn. BCA remains the structure generator: this class only prioritizes an
 * already-predicted start, waits for Minecraft to load an exterior chunk, and
 * asks vanilla's respawn validator for a safe block.
 */
public final class StarterTownBootstrap {
    private static final int APRON_DISTANCE = 32;
    private static final int MAX_STARTUP_CANDIDATES = 3;
    private static final int MAX_STARTUP_ROUTE_CANDIDATES = 12;
    private static final long REQUEST_TIMEOUT_TICKS = 400L;
    private static final Queue<Request> PENDING = new ArrayDeque<>();
    private static final Set<String> QUEUED = new HashSet<>();
    private static ActiveRequest active;
    private static long ticks;

    private StarterTownBootstrap() {}

    /** Called before the first prediction, preserving all existing world spawns. */
    public static void begin(MinecraftServer server) {
        ServerLevel level = LeagueMount.level(server);
        if (WorldLeaguePlan.get(level).beginStarterTown(level.getSharedSpawnPos())) {
            LeagueOrchestrator.LOGGER.info("New League world: reserving the closest realized town in the initial atlas as starter spawn.");
        }
    }

    /** Called after a complete site prediction has been recorded. */
    public static void considerPlannedTowns(ServerLevel level) {
        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        plan.reserveNearestStarterTown(level).ifPresent(town -> {
            if (town.bounds() != null) enqueueSpawnApron(level, town);
            else TownStartPrewarmer.enqueueStarter(level, town);
        });
    }

    /**
     * Runs before the server accepts its first player. A town start plus one
     * exterior chunk is a bounded and intentional startup cost; it replaces
     * the old asynchronous behavior that could only move spawn after a player
     * had already appeared at vanilla spawn.
     */
    public static void materializeBeforeFirstJoin(ServerLevel level) {
        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        for (int attempt = 0; attempt < MAX_STARTUP_CANDIDATES; attempt++) {
            TownAnchor town = plan.reserveNearestStarterTown(level).orElse(null);
            if (town == null) return;
            if (town.bounds() == null) town = TownStartPrewarmer.materializeBootstrapTownNow(level, town);
            if (town.bounds() != null && setSpawnFromApronNow(level, town)) {
                // RoadWeaver's placement-adapter API owns regional town
                // discovery and graph planning when available. The extra
                // bootstrap town is retained only for older RoadWeaver builds
                // that require League's realized-endpoint fallback.
                if (!RoadWeaverPlacementAdapterBridge.isInstalled()) {
                    materializeInitialRouteAnchor(level, town);
                }
                return;
            }
            if (!plan.rejectStarterTown(town)) return;
            LeagueOrchestrator.LOGGER.warn("Starter candidate {} was not usable; trying the next predicted town.", town.id());
        }
        LeagueOrchestrator.LOGGER.warn("No usable starter town was found within the bounded startup budget; retaining vanilla spawn.");
    }

    /**
     * RoadWeaver needs two real endpoints. Make one additional, nearest town
     * start available during the same bounded new-world bootstrap so a first
     * route exists without waiting for exploration or ticket retries.
     */
    private static void materializeInitialRouteAnchor(ServerLevel level, TownAnchor starter) {
        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        Set<String> attempted = new HashSet<>();
        for (int attempt = 0; attempt < MAX_STARTUP_ROUTE_CANDIDATES; attempt++) {
            TownAnchor candidate = plan.unrealizedTowns().stream()
                    .filter(town -> town.siteX() == starter.siteX() && town.siteZ() == starter.siteZ())
                    .filter(town -> !town.id().equals(starter.id()))
                    .filter(town -> !plan.wasStarterTownRejected(town))
                    .filter(town -> attempted.add(town.id()))
                    .min(Comparator.comparingDouble((TownAnchor town) -> town.center().distSqr(starter.center()))
                            .thenComparing(TownAnchor::id))
                    .orElse(null);
            if (candidate == null) break;
            TownAnchor realized = TownStartPrewarmer.materializeBootstrapTownNow(level, candidate);
            if (realized.bounds() == null) {
                LeagueOrchestrator.LOGGER.info("Initial route candidate {} was rejected by normal BCA generation.", candidate.id());
                continue;
            }
            // realizeSettlement has already rebuilt and published the graph.
            LeagueOrchestrator.LOGGER.info("Initial route anchor {} realized for starter town {}.",
                    realized.id(), starter.id());
            return;
        }
        LeagueOrchestrator.LOGGER.warn("Starter town {} has no second realized route anchor within the bounded startup budget.",
                starter.id());
    }

    /** Called only after Minecraft exposes the actual BCA start bounds. */
    public static void onTownRealized(ServerLevel level, TownAnchor town) {
        if (WorldLeaguePlan.get(level).isStarterSpawnPending(town)) enqueueSpawnApron(level, town);
    }

    /** Lets the prewarmer move to the next deterministic candidate if BCA rejects a start. */
    public static void onTownRejected(ServerLevel level, TownAnchor town) {
        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        RoadsideStopFallback.create(level, town);
        if (plan.rejectStarterTown(town)) considerPlannedTowns(level);
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        if (active != null) {
            tickActive(server);
            return;
        }
        Request request = PENDING.poll();
        if (request == null || request.level().getServer() != server || server.isStopped()) return;
        TownAnchor town = WorldLeaguePlan.get(request.level())
                .townAt(request.level(), request.town().center(), request.town().id()).orElse(null);
        if (town == null || town.bounds() == null || !WorldLeaguePlan.get(request.level()).isStarterSpawnPending(town)) {
            QUEUED.remove(request.key());
            return;
        }
        List<ChunkPos> aprons = apronChunks(town.bounds(), request.level().getSharedSpawnPos());
        if (aprons.isEmpty()) {
            QUEUED.remove(request.key());
            onTownRejected(request.level(), town);
            return;
        }
        beginAttempt(request, aprons, 0);
    }

    public static void stop() {
        if (active != null) release(active);
        active = null;
        PENDING.clear();
        QUEUED.clear();
        ticks = 0L;
    }

    private static void enqueueSpawnApron(ServerLevel level, TownAnchor town) {
        String key = key(level, town);
        if (QUEUED.add(key)) PENDING.add(new Request(level, town, key));
    }

    private static boolean setSpawnFromApronNow(ServerLevel level, TownAnchor town) {
        for (ChunkPos chunk : apronChunks(town.bounds(), level.getSharedSpawnPos())) {
            // This is called only from materializeBeforeFirstJoin. Do not use
            // it from ticks: normal retries remain ticketed and non-blocking.
            level.getChunk(chunk.x, chunk.z);
            BlockPos spawn = PlayerRespawnLogic.getSpawnPosInChunk(level, chunk);
            if (spawn == null || !outside(town.bounds(), spawn)) continue;
            if (WorldLeaguePlan.get(level).completeStarterSpawn(town, spawn).isEmpty()) return false;
            float angle = facingToward(spawn, town.center());
            level.setDefaultSpawnPos(spawn, angle);
            WorldLeaguePlan.get(level).publishRoadWeaverCampaign(level);
            PENDING.removeIf(request -> request.key().equals(key(level, town)));
            QUEUED.remove(key(level, town));
            LeagueOrchestrator.LOGGER.info("Starter town {} realized before first join; moved shared spawn to {}.",
                    town.id(), spawn.toShortString());
            return true;
        }
        return false;
    }

    private static void tickActive(MinecraftServer server) {
        ActiveRequest current = active;
        if (current.request().level().getServer() != server || server.isStopped()) {
            release(current);
            active = null;
            return;
        }
        if (ticks >= current.expiresAtTick()) {
            advanceOrReject(current, "timed out loading its exterior spawn chunk");
            return;
        }

        ServerLevel level = current.request().level();
        if (level.getChunkSource().getChunkNow(current.chunk().x, current.chunk().z) == null) return;
        BlockPos spawn = PlayerRespawnLogic.getSpawnPosInChunk(level, current.chunk());
        TownAnchor town = WorldLeaguePlan.get(level).townAt(level, current.request().town().center(), current.request().town().id()).orElse(null);
        if (town != null && spawn != null && outside(town.bounds(), spawn)) {
            WorldLeaguePlan.get(level).completeStarterSpawn(town, spawn).ifPresent(starter -> {
                float angle = facingToward(spawn, starter.center());
                level.setDefaultSpawnPos(spawn, angle);
                WorldLeaguePlan.get(level).publishRoadWeaverCampaign(level);
                LeagueOrchestrator.LOGGER.info("Starter town {} realized; moved shared spawn to {}.",
                        starter.id(), spawn.toShortString());
            });
            release(current);
            active = null;
            return;
        }
        advanceOrReject(current, "has no safe exterior respawn block in one apron chunk");
    }

    private static void beginAttempt(Request request, List<ChunkPos> chunks, int attempt) {
        ChunkPos chunk = chunks.get(attempt);
        request.level().getChunkSource().addRegionTicket(TicketType.UNKNOWN, chunk, 0, chunk);
        active = new ActiveRequest(request, chunks, attempt, chunk, ticks + REQUEST_TIMEOUT_TICKS);
    }

    private static void advanceOrReject(ActiveRequest current, String failure) {
        release(current);
        int nextAttempt = current.attempt() + 1;
        if (nextAttempt < current.aprons().size()) {
            beginAttempt(current.request(), current.aprons(), nextAttempt);
            return;
        }
        active = null;
        QUEUED.remove(current.request().key());
        LeagueOrchestrator.LOGGER.warn("Starter town {} {}. Trying the next predicted town, while keeping vanilla spawn safe.",
                current.request().town().id(), failure);
        onTownRejected(current.request().level(), current.request().town());
    }

    private static void release(ActiveRequest request) {
        request.request().level().getChunkSource().removeRegionTicket(TicketType.UNKNOWN, request.chunk(), 0, request.chunk());
    }

    private static List<ChunkPos> apronChunks(BoundingBox bounds, BlockPos origin) {
        int middleX = (bounds.minX() + bounds.maxX()) / 2;
        int middleZ = (bounds.minZ() + bounds.maxZ()) / 2;
        return List.of(
                        new BlockPos(bounds.minX() - APRON_DISTANCE, 0, middleZ),
                        new BlockPos(bounds.maxX() + APRON_DISTANCE, 0, middleZ),
                        new BlockPos(middleX, 0, bounds.minZ() - APRON_DISTANCE),
                        new BlockPos(middleX, 0, bounds.maxZ() + APRON_DISTANCE))
                .stream()
                .sorted(Comparator.comparingDouble(position -> position.distSqr(origin)))
                .map(ChunkPos::new)
                .distinct()
                .toList();
    }

    private static boolean outside(BoundingBox bounds, BlockPos position) {
        return position.getX() < bounds.minX() || position.getX() > bounds.maxX()
                || position.getZ() < bounds.minZ() || position.getZ() > bounds.maxZ();
    }

    private static float facingToward(BlockPos from, BlockPos to) {
        return (float) (Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()) * (180.0D / Math.PI) - 90.0D);
    }

    private static String key(ServerLevel level, TownAnchor town) {
        return level.dimension().location() + "|starter|" + town.siteX() + "," + town.siteZ() + "|" + town.id();
    }

    private record Request(ServerLevel level, TownAnchor town, String key) {}
    private record ActiveRequest(Request request, List<ChunkPos> aprons, int attempt, ChunkPos chunk, long expiresAtTick) {}
}
