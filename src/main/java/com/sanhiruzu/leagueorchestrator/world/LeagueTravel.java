package com.sanhiruzu.leagueorchestrator.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves players between the hub and the mounted League dimension, and
 * remembers where each player left from.
 *
 * <p>Return points live in world save data rather than on the player, because
 * Fabric has no persistent player data that survives death and dimension
 * change - which is exactly when a return point must not be lost.
 *
 * <p>Travel is a no-op when League is mounted in the main world: there is
 * nowhere to travel to, and saying so is better than silently doing nothing.
 */
public final class LeagueTravel extends SavedData {
    private static final String DATA_ID = "league_orchestrator_travel";

    private final Map<UUID, Return> returns = new HashMap<>();

    /** Where a player was standing when they entered the League dimension. */
    private record Return(ResourceKey<Level> dimension, BlockPos position) {
    }

    public static LeagueTravel get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(LeagueTravel::new, LeagueTravel::load, DataFixTypes.SAVED_DATA_MAP_INDEX),
                DATA_ID
        );
    }

    private static LeagueTravel load(CompoundTag tag, HolderLookup.Provider registries) {
        LeagueTravel data = new LeagueTravel();
        CompoundTag entries = tag.getCompound("returns");
        for (String key : entries.getAllKeys()) {
            CompoundTag entry = entries.getCompound(key);
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("dimension"));
            if (dimension == null) {
                continue;
            }
            try {
                data.returns.put(UUID.fromString(key), new Return(
                        ResourceKey.create(Registries.DIMENSION, dimension),
                        new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"))));
            } catch (IllegalArgumentException ignored) {
                // A malformed uuid is not worth failing a world load over.
            }
        }
        return data;
    }

    /** True when there is a separate League dimension to travel to. */
    public static boolean isAvailable(MinecraftServer server) {
        return !LeagueMount.level(server).dimension().equals(Level.OVERWORLD);
    }

    /**
     * Sends the player into the League dimension, recording where they left
     * from. Lands on the League world's shared spawn, which the starter-town
     * bootstrap has already validated as a safe outdoor block.
     */
    public static ServerLevel enter(ServerPlayer player) {
        MinecraftServer server = player.server;
        ServerLevel league = LeagueMount.level(server);
        LeagueTravel data = get(server);
        data.returns.put(player.getUUID(), new Return(player.level().dimension(), player.blockPosition()));
        data.setDirty();
        teleport(player, league, league.getSharedSpawnPos());
        return league;
    }

    /**
     * Sends the player back where they entered from, or to the hub's shared
     * spawn when nothing is recorded - a player who arrived some other way
     * still needs a way out.
     */
    public static ServerLevel leave(ServerPlayer player) {
        MinecraftServer server = player.server;
        LeagueTravel data = get(server);
        Return recorded = data.returns.remove(player.getUUID());
        data.setDirty();

        ServerLevel target = recorded == null ? null : server.getLevel(recorded.dimension());
        if (target == null || LeagueMount.isMounted(target)) {
            // Either nothing was recorded, the dimension is gone, or the
            // recorded point is inside the League world itself and would not
            // actually get the player out.
            target = server.overworld();
            teleport(player, target, target.getSharedSpawnPos());
            return target;
        }
        teleport(player, target, recorded.position());
        return target;
    }

    public Optional<BlockPos> returnPointOf(ServerPlayer player) {
        Return recorded = returns.get(player.getUUID());
        return Optional.ofNullable(recorded).map(Return::position);
    }

    private static void teleport(ServerPlayer player, ServerLevel level, BlockPos position) {
        player.teleportTo(level, position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag entries = new CompoundTag();
        returns.forEach((uuid, recorded) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("dimension", recorded.dimension().location().toString());
            entry.putInt("x", recorded.position().getX());
            entry.putInt("y", recorded.position().getY());
            entry.putInt("z", recorded.position().getZ());
            entries.put(uuid.toString(), entry);
        });
        tag.put("returns", entries);
        return tag;
    }
}
