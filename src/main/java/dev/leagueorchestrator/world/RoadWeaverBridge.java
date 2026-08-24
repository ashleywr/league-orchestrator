package dev.leagueorchestrator.world;

import dev.leagueorchestrator.LeagueOrchestrator;
import dev.leagueorchestrator.data.GymArchetype;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.lang.reflect.Method;

/**
 * Optional RoadWeaver hand-off for real, post-worldgen gym placements.
 *
 * RoadWeaver's static structure prediction cannot know that a gym has been
 * allocated to a particular town. Its public API can, however, register the
 * resulting endpoint and a direct town-to-gym connection. Reflection keeps
 * this module loadable in packs that do not include RoadWeaver.
 */
public final class RoadWeaverBridge {
    private static final String API_CLASS = "net.shiroha233.roadweaver.api.RoadNetworkApi";
    private static boolean unavailable;

    private RoadWeaverBridge() {}

    public static void connectPlacedGym(ServerLevel level, TownAnchor town, BlockPos gymPosition, GymArchetype gym) {
        if (unavailable || level != level.getServer().overworld()) return;
        try {
            Class<?> api = Class.forName(API_CLASS);
            Method register = api.getMethod("registerStructureEndpoint", ServerLevel.class, BlockPos.class, String.class, boolean.class);
            Method connect = api.getMethod("ensureConnection", ServerLevel.class, BlockPos.class, BlockPos.class, boolean.class);

            BlockPos townGate = townGate(level, town, gymPosition);
            register.invoke(null, level, townGate, "League town: " + town.id(), false);
            register.invoke(null, level, gymPosition, "League gym: " + gym.id(), false);
            // false leaves the short spur in RoadWeaver's normal async queue,
            // avoiding a second expensive worldgen operation in this tick.
            connect.invoke(null, level, townGate, gymPosition, false);
            LeagueOrchestrator.LOGGER.info("Registered RoadWeaver spur from town {} to {} at {}.",
                    town.id(), gym.id(), gymPosition.toShortString());
        } catch (ClassNotFoundException exception) {
            unavailable = true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            unavailable = true;
            LeagueOrchestrator.LOGGER.warn("RoadWeaver API is unavailable; gym {} remains reachable from its town without a generated spur.",
                    gym.id(), exception);
        }
    }

    private static BlockPos townGate(ServerLevel level, TownAnchor town, BlockPos gymPosition) {
        BoundingBox bounds = town.bounds();
        if (bounds == null) return town.center();

        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        int x;
        int z;
        if (Math.abs(gymPosition.getX() - centerX) >= Math.abs(gymPosition.getZ() - centerZ)) {
            x = gymPosition.getX() < centerX ? bounds.minX() - 8 : bounds.maxX() + 8;
            z = centerZ;
        } else {
            x = centerX;
            z = gymPosition.getZ() < centerZ ? bounds.minZ() - 8 : bounds.maxZ() + 8;
        }
        return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
    }
}
