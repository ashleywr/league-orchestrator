package dev.leagueorchestrator.world;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.leagueorchestrator.LeagueOrchestrator;
import dev.leagueorchestrator.data.GymArchetype;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Optional structure bridge. A gym archetype supplies a normal registered
 * structure ID (for example a Rad Gyms jigsaw structure); the server's own
 * {@code /place structure} implementation handles its placement. This keeps
 * League Orchestrator independent of any structure mod at compile time.
 */
public final class GymStructurePlacement {
    /**
     * Rad Gyms uses jigsaw structures with a 100-block maximum expansion from
     * their start. Vanilla's /place structure command insists every chunk in
     * the resulting bounding box is loaded before it places anything, so give
     * generic external gym structures a modest safety margin around that
     * documented maximum.
     */
    private static final int EXTERNAL_STRUCTURE_LOAD_RADIUS = 128;

    private GymStructurePlacement() {}

    public static boolean place(ServerLevel level, BlockPos position, GymArchetype gym) {
        return place(level, position, Direction.NORTH, gym);
    }

    public static boolean place(ServerLevel level, BlockPos position, Direction front, GymArchetype gym) {
        ResourceLocation structure = ResourceLocation.tryParse(gym.structureTag());
        if (structure == null) return false;

        if (structure.getNamespace().equals(LeagueOrchestrator.MOD_ID) && structure.getPath().equals("village_gym")) {
            return VillageGymBuilder.place(level, position, front, gym);
        }

        preloadPlacementArea(level, position, EXTERNAL_STRUCTURE_LOAD_RADIUS);

        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withPosition(Vec3.atBottomCenterOf(position))
                .withPermission(4)
                .withSuppressedOutput();
        String command = "place structure " + structure + " "
                + position.getX() + " " + position.getY() + " " + position.getZ();
        try {
            // 1.21's convenience server method is intentionally fire-and-
            // forget. Dispatching directly lets the ledger record a placed
            // gym only after /place structure actually succeeds.
            return level.getServer().getCommands().getDispatcher().execute(command, source) > 0;
        } catch (CommandSyntaxException exception) {
            LeagueOrchestrator.LOGGER.warn("Could not dispatch external gym structure {} at {}: {}",
                    structure, position.toShortString(), exception.getMessage());
            return false;
        }
    }

    /**
     * Returns only an author-declared leader anchor. League never guesses an
     * interior position for a third-party structure.
     */
    public static BlockPos leaderPosition(BlockPos origin, Direction front, GymArchetype gym) {
        ResourceLocation structure = ResourceLocation.tryParse(gym.structureTag());
        if (structure != null && structure.getNamespace().equals(LeagueOrchestrator.MOD_ID)
                && structure.getPath().equals("village_gym")) {
            return VillageGymBuilder.leaderPosition(origin, front);
        }
        return gym.leaderOffset() == null ? null : origin.offset(gym.leaderOffset());
    }

    private static void preloadPlacementArea(ServerLevel level, BlockPos center, int radius) {
        int minimumChunkX = (center.getX() - radius) >> 4;
        int maximumChunkX = (center.getX() + radius) >> 4;
        int minimumChunkZ = (center.getZ() - radius) >> 4;
        int maximumChunkZ = (center.getZ() + radius) >> 4;
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }
}
