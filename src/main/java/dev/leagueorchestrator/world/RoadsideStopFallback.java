package dev.leagueorchestrator.world;

import dev.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Turns a definitively rejected BCA town start into a small, durable route
 * terminus. It runs only after the authoritative start chunk is loaded and
 * contains no League settlement start, so it cannot create a marker from a
 * prediction alone.
 */
final class RoadsideStopFallback {
    private static final int SEARCH_RADIUS = 6;

    private RoadsideStopFallback() {}

    static boolean create(ServerLevel level, TownAnchor town) {
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
}
