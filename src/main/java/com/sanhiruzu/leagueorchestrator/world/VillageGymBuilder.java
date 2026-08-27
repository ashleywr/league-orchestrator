package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.data.GymArchetype;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The small, provider-free gym used by the bundled regional packs. It is kept
 * deliberately house-sized (17 by 13 blocks) so it can sit on a village edge
 * without consuming the footprint expected by BCA, vanilla, or other town
 * generators. Third-party archetypes can still use the external structure
 * bridge in {@link GymStructurePlacement}.
 */
public final class VillageGymBuilder {
    public static final int HALF_WIDTH = 8;
    public static final int HALF_DEPTH = 6;
    public static final int HEIGHT = 7;

    private VillageGymBuilder() {}

    public static boolean place(ServerLevelAccessor level, BlockPos origin, Direction front, GymArchetype gym) {
        if (!canBuildAt(level, origin, front)) return false;

        Palette palette = Palette.forGym(gym.id().getPath());
        clearBuildVolume(level, origin, front);

        // Foundation and a level floor make this reliable on the small slopes
        // which commonly surround village roads.
        for (int right = -HALF_WIDTH; right <= HALF_WIDTH; right++) {
            for (int depth = -HALF_DEPTH; depth <= HALF_DEPTH; depth++) {
                set(level, at(origin, front, right, depth, -1), Blocks.STONE_BRICKS.defaultBlockState());
                set(level, at(origin, front, right, depth, 0), palette.floor());
            }
        }

        for (int right = -HALF_WIDTH; right <= HALF_WIDTH; right++) {
            for (int depth = -HALF_DEPTH; depth <= HALF_DEPTH; depth++) {
                boolean edge = Math.abs(right) == HALF_WIDTH || Math.abs(depth) == HALF_DEPTH;
                if (!edge) continue;
                for (int height = 1; height <= 5; height++) {
                    // A three-block-wide, five-block-high open entrance lets
                    // path mods join the gym without needing door state data.
                    if (depth == -HALF_DEPTH && right >= -1 && right <= 1) continue;
                    BlockState state = (height == 5 || Math.abs(right) == HALF_WIDTH)
                            ? palette.trim() : palette.wall();
                    set(level, at(origin, front, right, depth, height), state);
                }
            }
        }

        // Poké Ball roof: an accent back half, a pale front half, and a dark
        // centre seam. The flat roof also stays visually quiet beside BCA's
        // more detailed buildings.
        for (int right = -HALF_WIDTH; right <= HALF_WIDTH; right++) {
            for (int depth = -HALF_DEPTH; depth <= HALF_DEPTH; depth++) {
                BlockState roof = depth < 0 ? palette.accent() : Blocks.WHITE_CONCRETE.defaultBlockState();
                if (depth == 0) roof = Blocks.BLACK_CONCRETE.defaultBlockState();
                if (right == 0 && depth == 0) roof = Blocks.IRON_BLOCK.defaultBlockState();
                set(level, at(origin, front, right, depth, 6), roof);
            }
        }

        // Entrance pillars, a compact battle court, and a leader dais give the
        // building a usable interior without a dependency on a trainer mod.
        for (int right : new int[]{-2, 2}) {
            for (int height = 1; height <= 4; height++) {
                set(level, at(origin, front, right, -HALF_DEPTH, height), palette.trim());
            }
            set(level, at(origin, front, right, -HALF_DEPTH, 5), Blocks.LANTERN.defaultBlockState());
        }
        for (int right = -4; right <= 4; right++) {
            for (int depth = -2; depth <= 3; depth++) {
                boolean ring = Math.abs(right) == 4 || depth == -2 || depth == 3;
                set(level, at(origin, front, right, depth, 1),
                        ring ? palette.accent() : Blocks.WHITE_CONCRETE.defaultBlockState());
            }
        }
        for (int right = -3; right <= 3; right++) {
            set(level, at(origin, front, right, 5, 1), palette.trim());
        }
        set(level, at(origin, front, 0, 5, 2), Blocks.GOLD_BLOCK.defaultBlockState());
        set(level, at(origin, front, -6, 4, 1), Blocks.CHEST.defaultBlockState());
        set(level, at(origin, front, 6, 4, 1), Blocks.CRAFTING_TABLE.defaultBlockState());
        return true;
    }

    /** The dais is the stable RCT leader home for this native building. */
    public static BlockPos leaderPosition(BlockPos origin, Direction front) {
        return at(origin, front, 0, 5, 3);
    }

    private static boolean canBuildAt(ServerLevelAccessor level, BlockPos origin, Direction front) {
        if (!level.getFluidState(origin.below()).isEmpty()) return false;
        for (int right = -HALF_WIDTH; right <= HALF_WIDTH; right++) {
            for (int depth = -HALF_DEPTH; depth <= HALF_DEPTH; depth++) {
                for (int height = 0; height <= HEIGHT; height++) {
                    BlockState state = level.getBlockState(at(origin, front, right, depth, height));
                    if (!canClear(state)) return false;
                }
            }
        }
        return true;
    }

    private static void clearBuildVolume(ServerLevelAccessor level, BlockPos origin, Direction front) {
        for (int right = -HALF_WIDTH; right <= HALF_WIDTH; right++) {
            for (int depth = -HALF_DEPTH; depth <= HALF_DEPTH; depth++) {
                for (int height = 0; height <= HEIGHT; height++) {
                    set(level, at(origin, front, right, depth, height), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** Allows ordinary terrain and foliage, while refusing to overwrite homes, paths, chests, or player builds. */
    private static boolean canClear(BlockState state) {
        return state.isAir() || state.canBeReplaced()
                || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL) || state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.DIRT_PATH) || state.is(Blocks.STONE) || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE) || state.is(Blocks.ANDESITE) || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY) || state.is(Blocks.MUD) || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.OAK_LOG) || state.is(Blocks.BIRCH_LOG)
                || state.is(Blocks.SPRUCE_LOG) || state.is(Blocks.JUNGLE_LOG) || state.is(Blocks.ACACIA_LOG)
                || state.is(Blocks.DARK_OAK_LOG) || state.is(Blocks.MANGROVE_LOG) || state.is(Blocks.CHERRY_LOG);
    }

    private static BlockPos at(BlockPos origin, Direction front, int right, int depth, int height) {
        return origin.relative(front.getClockWise(), right).relative(front, depth).above(height);
    }

    private static void set(ServerLevelAccessor level, BlockPos position, BlockState state) {
        level.setBlock(position, state, 3);
    }

    private record Palette(BlockState wall, BlockState trim, BlockState floor, BlockState accent) {
        private static Palette forGym(String id) {
            return switch (id) {
                case "kanto_water" -> new Palette(Blocks.PRISMARINE_BRICKS.defaultBlockState(), Blocks.DARK_PRISMARINE.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.BLUE_CONCRETE.defaultBlockState());
                case "kanto_electric" -> new Palette(Blocks.YELLOW_TERRACOTTA.defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.YELLOW_CONCRETE.defaultBlockState());
                case "kanto_grass" -> new Palette(Blocks.MOSS_BLOCK.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), Blocks.LIME_CONCRETE.defaultBlockState());
                case "kanto_poison" -> new Palette(Blocks.PURPLE_TERRACOTTA.defaultBlockState(), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), Blocks.PURPLE_CONCRETE.defaultBlockState());
                case "kanto_psychic" -> new Palette(Blocks.PINK_TERRACOTTA.defaultBlockState(), Blocks.QUARTZ_BRICKS.defaultBlockState(), Blocks.SMOOTH_QUARTZ.defaultBlockState(), Blocks.PINK_CONCRETE.defaultBlockState());
                case "kanto_fire" -> new Palette(Blocks.RED_TERRACOTTA.defaultBlockState(), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), Blocks.POLISHED_BLACKSTONE.defaultBlockState(), Blocks.RED_CONCRETE.defaultBlockState());
                case "kanto_ground" -> new Palette(Blocks.TERRACOTTA.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), Blocks.ORANGE_CONCRETE.defaultBlockState());
                case "kanto_rock" -> new Palette(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.GRAY_CONCRETE.defaultBlockState());
                default -> new Palette(Blocks.STONE_BRICKS.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState(), Blocks.SMOOTH_STONE.defaultBlockState(), Blocks.RED_CONCRETE.defaultBlockState());
            };
        }
    }
}
