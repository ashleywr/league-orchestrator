package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Deque;
import java.util.Set;

/**
 * Materializes one planned town start at a time through Minecraft's normal
 * chunk pipeline. This does not place blocks directly or execute BCA code:
 * the ticket asks Minecraft to generate the authoritative start chunk, where
 * the League structure placement and BCA jigsaw run normally.
 */
public final class TownStartPrewarmer {
    private static final long REQUEST_TIMEOUT_TICKS = 1_200L;
    private static final Deque<Request> PENDING = new ArrayDeque<>();
    private static final Set<String> KEYS = new HashSet<>();
    private static ActiveRequest active;
    private static long ticks;

    private TownStartPrewarmer() {}

    public static void enqueue(ServerLevel level, TownAnchor town) {
        if (!LeagueMount.isMounted(level) || town.bounds() != null) return;
        String key = key(level, town);
        if (KEYS.add(key)) PENDING.add(new Request(level, town.id(), town.center(), key));
    }

    /** Moves the selected new-world starter ahead of ordinary site warmup. */
    public static void enqueueStarter(ServerLevel level, TownAnchor town) {
        if (!LeagueMount.isMounted(level) || town.bounds() != null) return;
        String key = key(level, town);
        PENDING.removeIf(request -> request.key().equals(key));
        KEYS.add(key);
        PENDING.addFirst(new Request(level, town.id(), town.center(), key));
    }

    /**
     * The one deliberate synchronous worldgen operation: a single selected
     * new-world starter start before the first player joins. It uses the same
     * Minecraft/BCA pipeline as ticketed warmup and never runs for ordinary
     * towns or player-driven exploration.
     */
    public static TownAnchor materializeBootstrapTownNow(ServerLevel level, TownAnchor town) {
        if (!LeagueMount.isMounted(level) || town.bounds() != null) return town;
        String key = key(level, town);
        // This candidate was already queued by prediction. The synchronous
        // bootstrap owns it now, so do not later repeat the same failed BCA
        // check through a ticket.
        PENDING.removeIf(request -> request.key().equals(key));
        KEYS.remove(key);
        net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(town.center().getX() >> 4, town.center().getZ() >> 4);
        SettlementRealization.realize(level, chunk);
        return WorldLeaguePlan.get(level).townAt(level, town.center(), town.id()).orElse(town);
    }

    /** Restarts only incomplete town starts after a server restart. */
    public static void reconcile(MinecraftServer server) {
        ServerLevel level = LeagueMount.level(server);
        List<TownAnchor> towns = WorldLeaguePlan.get(level).unrealizedTowns();
        towns.forEach(town -> enqueue(level, town));
    }

    /** Called once per server end tick; at most one chunk ticket is live. */
    public static void tick(MinecraftServer server) {
        ticks++;
        if (active != null) {
            tickActive(server);
            return;
        }

        Request request = PENDING.poll();
        if (request == null || request.level().getServer() != server || server.isStopped()) return;
        TownAnchor town = WorldLeaguePlan.get(request.level()).townAt(request.level(), request.center(), request.townId()).orElse(null);
        if (town == null || town.bounds() != null) {
            KEYS.remove(request.key());
            return;
        }

        ChunkPos start = new ChunkPos(town.center());
        // Ask the normal asynchronous pipeline for one FULL chunk. A bare
        // region ticket can remain pending on C2ME without requesting status
        // upgrade; never replace this with synchronous level.getChunk here.
        request.level().getChunkSource().getChunkFuture(start.x, start.z, ChunkStatus.FULL, true);
        active = new ActiveRequest(request, start, ticks + REQUEST_TIMEOUT_TICKS);
    }

    public static void stop() {
        if (active != null) release(active);
        active = null;
        PENDING.clear();
        KEYS.clear();
        ticks = 0L;
    }

    private static void tickActive(MinecraftServer server) {
        ActiveRequest request = active;
        if (request.request().level().getServer() != server || server.isStopped()) {
            release(request);
            active = null;
            return;
        }

        LevelChunk loaded = request.request().level().getChunkSource().getChunkNow(request.start().x, request.start().z);
        if (loaded != null) {
            SettlementRealization.realize(request.request().level(), loaded);
            TownAnchor town = WorldLeaguePlan.get(request.request().level())
                    .townAt(request.request().level(), request.request().center(), request.request().townId()).orElse(null);
            if (town == null || town.bounds() == null) {
                LeagueOrchestrator.LOGGER.warn("Planned town {} did not realize at {}. Its other planned starts remain eligible as fallbacks.",
                        request.request().townId(), request.start());
                if (town != null) StarterTownBootstrap.onTownRejected(request.request().level(), town);
            }
            release(request);
            active = null;
            return;
        }

        if (ticks >= request.expiresAtTick()) {
            LeagueOrchestrator.LOGGER.warn("Timed out waiting for planned town start chunk {}.", request.start());
            release(request);
            active = null;
        }
    }

    private static void release(ActiveRequest request) {
        // getChunkFuture owns its short-lived request; there is no custom
        // ticket to revoke or accidentally retain after a rejected start.
        KEYS.remove(request.request().key());
    }

    private static String key(ServerLevel level, TownAnchor town) {
        return level.dimension().location() + "|" + town.siteX() + "," + town.siteZ() + "|" + town.id();
    }

    private record Request(ServerLevel level, String townId, net.minecraft.core.BlockPos center, String key) {}
    private record ActiveRequest(Request request, ChunkPos start, long expiresAtTick) {}
}
