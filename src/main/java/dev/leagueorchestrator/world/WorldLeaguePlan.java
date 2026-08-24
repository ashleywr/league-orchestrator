package dev.leagueorchestrator.world;

import dev.leagueorchestrator.data.GymArchetype;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.regionengine.policy.RegionPolicyService;
import dev.regionengine.world.atlas.AtlasRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The server's audit-friendly allocation ledger. An entry is never rerolled
 * during normal play: it is created once for a site/slot and saved with the
 * world. The pool is depleted globally, which lets content emulate a locale's
 * unique leaders or instead use one pool per elemental type.
 */
public final class WorldLeaguePlan extends SavedData {
    private static final String DATA_ID = "league_orchestrator_world_plan";
    private static final Factory<WorldLeaguePlan> FACTORY = new Factory<>(WorldLeaguePlan::new, WorldLeaguePlan::load, null);

    private final Map<String, String> allocations = new HashMap<>();

    public static void ensureAllOverworldPlans(MinecraftServer server) {
        get(server.overworld());
    }

    public static WorldLeaguePlan get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public synchronized Optional<GymArchetype> allocate(ServerLevel level, BlockPos position, String slot, String pool) {
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), position.getX(), position.getZ());
        if (atlas.kind() == AtlasRegion.Kind.OCEAN) {
            return Optional.empty();
        }

        String site = atlas.cellX() + "," + atlas.cellZ();
        String allocationKey = site + "|" + slot;
        String existing = allocations.get(allocationKey);
        if (existing != null) {
            return Optional.ofNullable(LeagueDataReloadListener.current().gymArchetypes()
                    .get(net.minecraft.resources.ResourceLocation.tryParse(existing)));
        }

        String profileId = RegionPolicyService.profileForSite(level.getSeed(), atlas.cellX(), atlas.cellZ()).id().toString();
        List<GymArchetype> candidates = new ArrayList<>(LeagueDataReloadListener.current().gymsInPool(pool, profileId));
        candidates.removeIf(gym -> allocations.containsValue(gym.id().toString()));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        GymArchetype selected = weightedPick(candidates, level.getSeed(), allocationKey);
        allocations.put(allocationKey, selected.id().toString());
        setDirty();
        return Optional.of(selected);
    }

    public synchronized Optional<String> allocationAt(ServerLevel level, BlockPos position, String slot) {
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), position.getX(), position.getZ());
        return Optional.ofNullable(allocations.get(atlas.cellX() + "," + atlas.cellZ() + "|" + slot));
    }

    private static GymArchetype weightedPick(List<GymArchetype> candidates, long worldSeed, String allocationKey) {
        candidates.sort(Comparator.comparing(gym -> gym.id().toString()));
        int total = candidates.stream().mapToInt(GymArchetype::weight).sum();
        int roll = RandomSource.create(worldSeed ^ allocationKey.hashCode()).nextInt(total);
        for (GymArchetype candidate : candidates) {
            roll -= candidate.weight();
            if (roll < 0) return candidate;
        }
        return candidates.getLast();
    }

    private static WorldLeaguePlan load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        WorldLeaguePlan plan = new WorldLeaguePlan();
        ListTag entries = tag.getList("allocations", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            plan.allocations.put(entry.getString("key"), entry.getString("gym"));
        }
        return plan;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        allocations.forEach((key, gym) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("key", key);
            entry.putString("gym", gym);
            entries.add(entry);
        });
        tag.put("allocations", entries);
        return tag;
    }
}
