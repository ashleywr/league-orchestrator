package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Turns a definitively rejected BCA town start into a small, durable route
 * terminus. It runs only after the authoritative start chunk is loaded and
 * contains no League settlement start, so it cannot create a marker from a
 * prediction alone.
 */
public final class RoadsideStopFallback {
    private static final int SEARCH_RADIUS = 6;
    private static final Queue<Request> PENDING = new ArrayDeque<>();
    private static final Set<String> KEYS = new HashSet<>();

    private RoadsideStopFallback() {}

    /**
     * A chunk-load callback may run while C2ME is still upgrading the very
     * chunk which triggered it.  Do not query blocks or heightmaps there:
     * defer the cosmetic marker to the normal end-of-tick path instead.
     */
    static void defer(ServerLevel level, TownAnchor town) {
        if (!LeagueMount.isMounted(level) || town == null) return;
        String key = level.dimension().location() + "|roadside|" + town.id();
        if (KEYS.add(key)) PENDING.add(new Request(level, town, key));
    }

    public static void tick(MinecraftServer server) {
        Request request = PENDING.poll();
        if (request == null) return;
        KEYS.remove(request.key());
        if (request.level().getServer() != server || server.isStopped()) return;
        ChunkPos chunk = new ChunkPos(request.town().center());
        // getChunkNow is explicitly non-loading.  If the structure event did
        // not leave its chunk available, skip the optional sign rather than
        // turning a rejected town into a worldgen request.
        if (request.level().getChunkSource().getChunkNow(chunk.x, chunk.z) == null) return;
        createLoaded(request.level(), request.town());
    }

    public static void stop() {
        PENDING.clear();
        KEYS.clear();
    }

    private static boolean createLoaded(ServerLevel level, TownAnchor town) {
        if (!LeagueMount.isMounted(level) || town == null) return false;
        WorldLeaguePlan plan = WorldLeaguePlan.get(level);
        if (plan.isRoadsideStop(town)) return false;

        BlockPos marker = findMarkerPosition(level, town.center());
        if (marker == null || !plan.markRoadsideStop(level, town, marker)) return false;

        level.setBlock(marker, Blocks.OAK_SIGN.defaultBlockState(), 3);
        if (level.getBlockEntity(marker) instanceof SignBlockEntity sign) {
            SignText text = sign.getText(true)
                    .setMessage(0, Component.literal("Survey Post"))
                    .setMessage(1, Component.literal("Settlement Site"))
                    .setMessage(2, Component.literal("Route Continues"));
            sign.setText(text, true);
            sign.setChanged();
        }
        LeagueOrchestrator.LOGGER.info("Planned town {} did not generate; placed a roadside survey post at {}.",
                town.id(), marker.toShortString());
        return true;
    }

    private static BlockPos findMarkerPosition(ServerLevel level, BlockPos center) {
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = center.getX() + dx;
                    int z = center.getZ() + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (level.getBlockState(candidate).isAir() && level.getBlockState(candidate.above()).isAir()) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private record Request(ServerLevel level, TownAnchor town, String key) {}
}
