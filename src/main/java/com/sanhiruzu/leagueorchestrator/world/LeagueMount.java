package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.config.LeagueConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Resolves the dimension League content is mounted in.
 *
 * <p>League generates into exactly one dimension per server. A pack played
 * solo mounts in the main world; a server may instead mount League in a
 * dedicated dimension and keep a separate persistent overworld as its social
 * hub. Every League subsystem that needs "the League world" asks here rather
 * than assuming {@code server.overworld()}, so that choice stays in one place.
 *
 * <p>The mount point is server policy, read from LeagueConfig. Only
 * {@code main_world} is implemented, so this still resolves to the overworld;
 * an unimplemented value is refused during config validation rather than
 * silently changing where a campaign generates.
 *
 * <p>Note that {@link WorldLeaguePlan} is per-level {@link
 * net.minecraft.world.level.saveddata.SavedData}, so a plan follows its mounted
 * dimension without migration.
 */
public final class LeagueMount {
    private LeagueMount() {
    }

    /** The level League generates and plans into. */
    public static ServerLevel level(MinecraftServer server) {
        // Config validation reduces mount to one League can honour, and
        // main_world is the only one implemented. When a League dimension
        // exists, this is where it is chosen.
        assert LeagueConfig.MOUNT_MAIN_WORLD.equals(LeagueConfig.get().mount);
        return server.overworld();
    }

    /** True when this level is the mounted League dimension. */
    public static boolean isMounted(ServerLevel level) {
        return level == level(level.getServer());
    }

    /** True when this dimension id names the mounted League dimension. */
    public static boolean isMounted(MinecraftServer server, ResourceLocation dimension) {
        return level(server).dimension().location().equals(dimension);
    }
}
