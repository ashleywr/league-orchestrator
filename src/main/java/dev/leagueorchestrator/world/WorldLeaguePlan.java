package dev.leagueorchestrator.world;

import dev.leagueorchestrator.data.GymArchetype;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.regionengine.policy.RegionPolicyService;
import dev.regionengine.world.atlas.AtlasRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

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
    private final Map<String, TownAnchor> towns = new HashMap<>();

    public static void ensureAllOverworldPlans(MinecraftServer server) {
        get(server.overworld());
    }

    /** Re-publish saved gym endpoints after a restart when RoadWeaver is installed. */
    public static void reconcileRoadWeaverGyms(MinecraftServer server) {
        ServerLevel level = server.overworld();
        get(level).connectPlacedGyms(level);
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
        // Pools deplete within an atlas site. Kanto can therefore have one of
        // each selected gym across its towns, while a future Johto profile can
        // intentionally reuse the same archetype from its own local pool.
        candidates.removeIf(gym -> allocatedAtSite(site, gym.id().toString()));
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

    public synchronized Optional<TownAnchor> registerTown(ServerLevel level, BlockPos center, String townId) {
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), center.getX(), center.getZ());
        if (atlas.kind() != AtlasRegion.Kind.CONTINENT) return Optional.empty();
        String key = townKey(atlas.cellX(), atlas.cellZ(), townId);
        TownAnchor existing = towns.get(key);
        if (existing != null) return Optional.of(existing);

        TownAnchor town = new TownAnchor(townId, atlas.cellX(), atlas.cellZ(), center, null, null);
        towns.put(key, town);
        setDirty();
        return Optional.of(town);
    }

    public synchronized Optional<TownAnchor> townAt(ServerLevel level, BlockPos position, String townId) {
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), position.getX(), position.getZ());
        return Optional.ofNullable(towns.get(townKey(atlas.cellX(), atlas.cellZ(), townId)));
    }

    public synchronized Optional<TownAnchor> nearestTown(ServerLevel level, BlockPos position, double maximumDistance) {
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), position.getX(), position.getZ());
        double maximumDistanceSquared = maximumDistance * maximumDistance;
        return towns.values().stream()
                .filter(town -> town.siteX() == atlas.cellX() && town.siteZ() == atlas.cellZ())
                .filter(town -> town.center().distSqr(position) <= maximumDistanceSquared)
                .min(Comparator.comparingDouble(town -> town.center().distSqr(position)));
    }

    public synchronized Optional<TownAnchor> discoverTown(ServerLevel level, String townId, BoundingBox bounds) {
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        int centerY = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), centerX, centerZ);
        if (atlas.kind() != AtlasRegion.Kind.CONTINENT) return Optional.empty();

        String key = townKey(atlas.cellX(), atlas.cellZ(), townId);
        TownAnchor existing = towns.get(key);
        if (existing != null) return Optional.of(existing);

        TownAnchor town = new TownAnchor(townId, atlas.cellX(), atlas.cellZ(), new BlockPos(centerX, centerY, centerZ), bounds, null);
        towns.put(key, town);
        setDirty();
        return Optional.of(town);
    }

    public synchronized Optional<GymArchetype> gymForTown(ServerLevel level, TownAnchor town, String pool) {
        return allocate(level, town.center(), "town:" + town.id(), pool);
    }

    public synchronized boolean markGymPlaced(TownAnchor town, BlockPos position) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymPosition() != null) return false;
        towns.put(key, current.withGymPosition(position));
        setDirty();
        return true;
    }

    private void connectPlacedGyms(ServerLevel level) {
        towns.values().forEach(town -> {
            if (town.gymPosition() == null) return;
            String allocationKey = town.siteX() + "," + town.siteZ() + "|town:" + town.id();
            ResourceLocation gymId = ResourceLocation.tryParse(allocations.get(allocationKey));
            GymArchetype gym = gymId == null ? null : LeagueDataReloadListener.current().gymArchetypes().get(gymId);
            if (gym != null) RoadWeaverBridge.connectPlacedGym(level, town, town.gymPosition(), gym);
        });
    }

    private boolean allocatedAtSite(String site, String gymId) {
        String prefix = site + "|";
        return allocations.entrySet().stream()
                .anyMatch(entry -> entry.getKey().startsWith(prefix) && entry.getValue().equals(gymId));
    }

    private static String townKey(int siteX, int siteZ, String townId) {
        return siteX + "," + siteZ + "|" + townId;
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
        ListTag townEntries = tag.getList("towns", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < townEntries.size(); index++) {
            TownAnchor town = TownAnchor.load(townEntries.getCompound(index));
            plan.towns.put(townKey(town.siteX(), town.siteZ(), town.id()), town);
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
        ListTag townEntries = new ListTag();
        towns.values().stream()
                .sorted(Comparator.comparing(town -> townKey(town.siteX(), town.siteZ(), town.id())))
                .map(TownAnchor::save)
                .forEach(townEntries::add);
        tag.put("towns", townEntries);
        return tag;
    }
}
