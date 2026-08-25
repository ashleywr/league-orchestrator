package dev.leagueorchestrator.world;

import dev.leagueorchestrator.LeagueOrchestrator;
import dev.leagueorchestrator.data.GymArchetype;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Defers gym construction until a previously predicted settlement has exposed
 * its completed bounding box. One placement is attempted per tick to avoid
 * recursive worldgen while a burst of realized structures arrives together.
 */
public final class AutoTownGymPlacement {
    private static final int LOT_CLEARANCE = 40;
    /**
     * Towns are the road network; only a deterministic subset becomes a gym
     * destination. At the current Kanto town density this normally leaves a
     * few ordinary settlements between the eight available badge slots.
     */
    private static final int GYM_TOWN_ELIGIBILITY_DENOMINATOR = 3;
    private static final int GYM_TOWN_ELIGIBILITY_NUMERATOR = 2;
    private static final Queue<Request> PENDING = new ArrayDeque<>();
    private static final Set<String> PENDING_KEYS = new HashSet<>();

    private AutoTownGymPlacement() {}

    public static void enqueue(ServerLevel level, TownAnchor town) {
        String key = level.dimension().location() + "|" + town.siteX() + "," + town.siteZ() + "|" + town.id();
        if (town.gymPosition() == null && PENDING_KEYS.add(key)) PENDING.add(new Request(level, town, key));
    }

    public static void tick(MinecraftServer server) {
        Request request = PENDING.poll();
        if (request == null || request.level().getServer() != server) return;
        PENDING_KEYS.remove(request.key());

        WorldLeaguePlan plan = WorldLeaguePlan.get(request.level());
        TownAnchor town = plan.townAt(request.level(), request.town().center(), request.town().id())
                .orElse(null);
        if (town == null) return;
        if (town.gymPosition() != null) return;
        if (!isGymEligible(request.level(), town)) return;
        String gymPool = plan.campaignFor(request.level(), town.center())
                .map(dev.leagueorchestrator.data.CampaignDefinition::gymPool)
                .orElse("");
        if (gymPool.isBlank()) return;
        Optional<GymArchetype> gym = plan.gymForTown(request.level(), town, gymPool);
        if (gym.isEmpty()) return;

        BlockPos lot = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            BlockPos candidate = lotFor(request.level(), town, attempt);
            if (GymStructurePlacement.place(request.level(), candidate, frontToward(candidate, town.center()), gym.get())) {
                lot = candidate;
                break;
            }
        }
        if (lot != null) {
            BlockPos leaderPosition = GymStructurePlacement.leaderPosition(lot, frontToward(lot, town.center()), gym.get());
            plan.markGymPlaced(town, lot, leaderPosition, !gym.get().trainerId().isBlank());
            RoadWeaverBridge.connectPlacedGym(request.level(), town, lot, gym.get());
            RctGymLeaderBridge.enqueue(request.level(), town, gym.get());
            LeagueOrchestrator.LOGGER.info("Placed {} beside generated town {} at {}.", gym.get().id(), town.id(), lot.toShortString());
        } else {
            LeagueOrchestrator.LOGGER.warn("Could not find a clear compact lot beside generated town {} for {}. Its allocation remains reserved.",
                    town.id(), gym.get().id());
        }
    }

    static boolean isGymEligible(ServerLevel level, TownAnchor town) {
        long hash = level.getSeed()
                ^ ((long) town.siteX() * 0x9E3779B97F4A7C15L)
                ^ ((long) town.siteZ() * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) town.id().hashCode() * 0x165667B19E3779F9L);
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return Math.floorMod((int) hash, GYM_TOWN_ELIGIBILITY_DENOMINATOR)
                < GYM_TOWN_ELIGIBILITY_NUMERATOR;
    }

    private static BlockPos lotFor(ServerLevel level, TownAnchor town, int attempt) {
        BoundingBox bounds = town.bounds();
        if (bounds == null) return town.center();

        int choice = Math.floorMod((int) (level.getSeed() ^ town.id().hashCode()) + attempt, 4);
        int x = (bounds.minX() + bounds.maxX()) / 2;
        int z = (bounds.minZ() + bounds.maxZ()) / 2;
        switch (choice) {
            case 0 -> z = bounds.minZ() - LOT_CLEARANCE;
            case 1 -> x = bounds.maxX() + LOT_CLEARANCE;
            case 2 -> z = bounds.maxZ() + LOT_CLEARANCE;
            default -> x = bounds.minX() - LOT_CLEARANCE;
        }
        // A town's adjacent lot can be in a not-yet-generated chunk. Generate
        // its start chunk before consulting the heightmap; otherwise Minecraft
        // reports the minimum build height (-64) and the jigsaw command fails.
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
    }

    private static net.minecraft.core.Direction frontToward(BlockPos from, BlockPos to) {
        int deltaX = to.getX() - from.getX();
        int deltaZ = to.getZ() - from.getZ();
        if (Math.abs(deltaX) > Math.abs(deltaZ)) return deltaX > 0
                ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST;
        return deltaZ > 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH;
    }

    private record Request(ServerLevel level, TownAnchor town, String key) {}
}
