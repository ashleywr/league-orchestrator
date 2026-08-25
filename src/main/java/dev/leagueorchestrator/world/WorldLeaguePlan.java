package dev.leagueorchestrator.world;

import dev.leagueorchestrator.data.GymArchetype;
import dev.leagueorchestrator.data.CampaignDefinition;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.regionengine.policy.RegionPolicyService;
import dev.regionengine.world.atlas.AtlasRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

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
    private final Map<String, CampaignRoute> campaignRoutes = new HashMap<>();
    private final Set<String> plannedSites = new HashSet<>();
    private final Map<String, String> legendaryClaims = new HashMap<>();
    private final Set<String> claimedRewards = new HashSet<>();
    private final Map<String, String> campaignsByProfile = new HashMap<>();
    private final Map<String, String> rctSeriesByProfile = new HashMap<>();
    /** Read once only to migrate worlds created during the global-campaign prototype. */
    private String legacyCampaignId = "";

    public static void ensureAllOverworldPlans(MinecraftServer server) {
        get(server.overworld());
    }

    /** Re-publish the saved campaign graph after a restart when RoadWeaver is installed. */
    public static void reconcileRoadWeaverCampaign(MinecraftServer server) {
        ServerLevel level = server.overworld();
        get(level).publishRoadWeaverCampaign(level);
    }

    /** Requeue saved gym anchors so persistent RCT leaders survive restarts and repairs. */
    public static void reconcileRctGymLeaders(MinecraftServer server) {
        ServerLevel level = server.overworld();
        get(level).enqueueRctGymLeaders(level);
    }

    /** Revisits only the newly loaded chunk; this never asks Minecraft to load terrain for an addon. */
    public static void reconcileRctGymLeadersInChunk(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (level != level.getServer().overworld()) return;
        get(level).enqueueRctGymLeadersInChunk(level, chunk.getPos().x, chunk.getPos().z);
    }

    public static WorldLeaguePlan get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    /**
     * Resolves a campaign for the active Region Engine profile. The first valid
     * match is saved per profile, allowing a newly-added pack to cover an
     * untouched region without changing a profile that players have visited.
     */
    public synchronized Optional<CampaignDefinition> campaignFor(ServerLevel level, BlockPos position) {
        ResourceLocation profileId = RegionPolicyService.profileAt(level, position.getX(), position.getZ()).id();
        String stored = campaignsByProfile.get(profileId.toString());
        if (stored != null) {
            ResourceLocation storedId = ResourceLocation.tryParse(stored);
            Optional<CampaignDefinition> campaign = storedId == null ? Optional.empty() : LeagueDataReloadListener.current().campaign(storedId);
            campaign.filter(value -> !value.rctSeriesId().isBlank()).ifPresent(value -> {
                if (rctSeriesByProfile.putIfAbsent(profileId.toString(), value.rctSeriesId()) == null) setDirty();
            });
            return campaign;
        }

        ResourceLocation legacy = ResourceLocation.tryParse(legacyCampaignId);
        Optional<CampaignDefinition> resolved = legacy == null
                ? LeagueDataReloadListener.current().campaignForProfile(profileId)
                : LeagueDataReloadListener.current().campaign(legacy);
        resolved.ifPresent(campaign -> {
            campaignsByProfile.put(profileId.toString(), campaign.id().toString());
            if (!campaign.rctSeriesId().isBlank()) rctSeriesByProfile.put(profileId.toString(), campaign.rctSeriesId());
            legacyCampaignId = "";
            setDirty();
        });
        return resolved;
    }

    public synchronized Optional<ResourceLocation> campaignIdFor(ServerLevel level, BlockPos position) {
        return campaignFor(level, position).map(CampaignDefinition::id);
    }

    /** The RCT series chosen with this profile's campaign, if the campaign uses RCT. */
    public synchronized Optional<String> rctSeriesIdFor(ServerLevel level, BlockPos position) {
        campaignFor(level, position);
        ResourceLocation profileId = RegionPolicyService.profileAt(level, position.getX(), position.getZ()).id();
        return Optional.ofNullable(rctSeriesByProfile.get(profileId.toString()));
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

        TownAnchor town = new TownAnchor(townId, atlas.cellX(), atlas.cellZ(), center, null, null, center,
                null, null, GymLeaderState.UNASSIGNED, "");
        towns.put(key, town);
        setDirty();
        RoadWeaverBridge.publishTown(level, town);
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

    /** Records a deterministic structure-start prediction without loading a chunk or consulting a heightmap. */
    public synchronized Optional<TownAnchor> planSettlement(ServerLevel level, ResourceLocation structure, BlockPos predictedStart) {
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), predictedStart.getX(), predictedStart.getZ());
        if (atlas.kind() != AtlasRegion.Kind.CONTINENT) return Optional.empty();
        String townId = settlementId(structure, new net.minecraft.world.level.ChunkPos(predictedStart));
        String key = townKey(atlas.cellX(), atlas.cellZ(), townId);
        TownAnchor existing = towns.get(key);
        if (existing != null) return Optional.of(existing);

        TownAnchor town = new TownAnchor(townId, atlas.cellX(), atlas.cellZ(),
                new BlockPos(predictedStart.getX(), level.getMinBuildHeight(), predictedStart.getZ()), null, null,
                new BlockPos(predictedStart.getX(), level.getMinBuildHeight(), predictedStart.getZ()), null, null,
                GymLeaderState.UNASSIGNED, "");
        towns.put(key, town);
        setDirty();
        RoadWeaverBridge.publishTown(level, town);
        return Optional.of(town);
    }

    /** Attaches the generated bounding box to a prediction; unknown starts are intentionally ignored. */
    public synchronized Optional<TownAnchor> realizeSettlement(ServerLevel level, ResourceLocation structure,
                                                                 net.minecraft.world.level.ChunkPos startChunk, BoundingBox bounds) {
        String townId = settlementId(structure, startChunk);
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerY = (bounds.minY() + bounds.maxY()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(level.getSeed(), centerX, centerZ);
        TownAnchor existing = towns.get(townKey(atlas.cellX(), atlas.cellZ(), townId));
        if (existing == null) return Optional.empty();
        if (existing.bounds() != null) return Optional.of(existing);
        TownAnchor realized = existing.withRealizedBounds(new BlockPos(centerX, centerY, centerZ), bounds);
        towns.put(townKey(existing.siteX(), existing.siteZ(), existing.id()), realized);
        setDirty();
        return Optional.of(realized);
    }

    public synchronized boolean isSitePlanned(int siteX, int siteZ) {
        return plannedSites.contains(siteX + "," + siteZ);
    }

    public synchronized void markSitePlanned(int siteX, int siteZ) {
        if (plannedSites.add(siteX + "," + siteZ)) setDirty();
    }

    /**
     * Builds the initial, sparse road topology for one predicted atlas site.
     * It is a deterministic minimum-distance tree rather than a level order:
     * every town is reachable, but the graph leaves room for future planned
     * shortcuts, caves, ferries, and gates to add non-linear progression.
     */
    public synchronized void ensureSiteRoadGraph(int siteX, int siteZ) {
        List<String> siteTowns = towns.entrySet().stream()
                .filter(entry -> entry.getValue().siteX() == siteX && entry.getValue().siteZ() == siteZ)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (siteTowns.size() < 2) return;

        Set<String> connected = new HashSet<>();
        connected.add(siteTowns.getFirst());
        while (connected.size() < siteTowns.size()) {
            RouteCandidate best = null;
            for (String fromKey : connected) {
                for (String toKey : siteTowns) {
                    if (connected.contains(toKey)) continue;
                    RouteCandidate candidate = new RouteCandidate(fromKey, toKey, townDistanceSquared(fromKey, toKey));
                    if (best == null || candidate.compareTo(best) < 0) best = candidate;
                }
            }
            if (best == null) return;
            CampaignRoute route = CampaignRoute.between(best.fromTownKey(), best.toTownKey());
            if (campaignRoutes.putIfAbsent(route.id(), route) == null) setDirty();
            connected.add(best.toTownKey());
        }
    }

    public synchronized Optional<GymArchetype> gymForTown(ServerLevel level, TownAnchor town, String pool) {
        return allocate(level, town.center(), "town:" + town.id(), pool);
    }

    public synchronized boolean markGymPlaced(TownAnchor town, BlockPos position, BlockPos leaderPosition, boolean leaderRequested) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymPosition() != null) return false;
        towns.put(key, current.withGymPosition(position, leaderPosition, leaderRequested));
        setDirty();
        return true;
    }

    public synchronized boolean markGymLeaderSpawned(TownAnchor town, java.util.UUID leaderUuid) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymLeaderPosition() == null) return false;
        towns.put(key, current.withGymLeaderUuid(leaderUuid));
        setDirty();
        return true;
    }

    /** Marks the durable intent as inside a potentially side-effecting provider call. */
    public synchronized boolean markGymLeaderVerifying(TownAnchor town) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymLeaderPosition() == null || current.gymLeaderState() != GymLeaderState.PENDING) return false;
        towns.put(key, current.withGymLeaderState(GymLeaderState.VERIFYING, ""));
        setDirty();
        return true;
    }

    /** Reopens a verified-missing persistent leader only after exact provider reconciliation. */
    public synchronized boolean markGymLeaderPending(TownAnchor town) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymLeaderPosition() == null || current.gymLeaderState() == GymLeaderState.DEGRADED) return false;
        towns.put(key, current.withGymLeaderState(GymLeaderState.PENDING, ""));
        setDirty();
        return true;
    }

    /** Persists a stop condition which must not be retried automatically. */
    public synchronized boolean markGymLeaderDegraded(TownAnchor town, String failure) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymLeaderPosition() == null) return false;
        towns.put(key, current.withGymLeaderState(GymLeaderState.DEGRADED, failure));
        setDirty();
        return true;
    }

    /** Explicit operator recovery. The bridge still performs exact reconciliation before any new provider call. */
    public synchronized Optional<TownAnchor> resetGymLeader(TownAnchor town) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymLeaderPosition() == null || current.gymLeaderState() != GymLeaderState.DEGRADED) {
            return Optional.empty();
        }
        TownAnchor reset = current.withGymLeaderState(GymLeaderState.PENDING, "");
        towns.put(key, reset);
        setDirty();
        return Optional.of(reset);
    }

    /** Migrates old placed gyms without an RCT trainer out of a pending leader state. */
    public synchronized boolean markGymLeaderUnassigned(TownAnchor town) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        TownAnchor current = towns.get(key);
        if (current == null || current.gymLeaderPosition() == null || current.gymLeaderState() == GymLeaderState.ACTIVE) return false;
        towns.put(key, current.withGymLeaderState(GymLeaderState.UNASSIGNED, ""));
        setDirty();
        return true;
    }

    /** Records the first claimant of a planned legendary encounter. */
    public synchronized boolean claimLegendary(ResourceLocation legendaryId, java.util.UUID claimant) {
        if (legendaryClaims.containsKey(legendaryId.toString())) return false;
        legendaryClaims.put(legendaryId.toString(), claimant.toString());
        setDirty();
        return true;
    }

    /** Rewards are idempotent per player, even if an integration retries after a disconnect. */
    public synchronized boolean claimReward(ResourceLocation rewardId, java.util.UUID playerId) {
        String key = playerId + "|" + rewardId;
        if (!claimedRewards.add(key)) return false;
        setDirty();
        return true;
    }

    /** Publishes only the ledger's selected endpoints and edges to RoadWeaver. */
    public synchronized void publishRoadWeaverCampaign(ServerLevel level) {
        towns.values().forEach(town -> RoadWeaverBridge.publishTown(level, town));
        campaignRoutes.values().stream().sorted(Comparator.comparing(CampaignRoute::id)).forEach(route -> {
            TownAnchor from = towns.get(route.fromTownKey());
            TownAnchor to = towns.get(route.toTownKey());
            if (from != null && to != null) RoadWeaverBridge.connectCampaignRoute(level, route.id(), from, to);
        });
        connectPlacedGyms(level);
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

    private void enqueueRctGymLeaders(ServerLevel level) {
        towns.values().forEach(town -> {
            if (town.gymPosition() == null || town.gymLeaderPosition() == null) return;
            if (town.gymLeaderState() == GymLeaderState.DEGRADED || town.gymLeaderState() == GymLeaderState.UNASSIGNED) return;
            if (level.getChunkSource().getChunkNow(town.gymLeaderPosition().getX() >> 4,
                    town.gymLeaderPosition().getZ() >> 4) == null) return;
            String allocationKey = town.siteX() + "," + town.siteZ() + "|town:" + town.id();
            ResourceLocation gymId = ResourceLocation.tryParse(allocations.get(allocationKey));
            GymArchetype gym = gymId == null ? null : LeagueDataReloadListener.current().gymArchetypes().get(gymId);
            if (gym != null) RctGymLeaderBridge.enqueue(level, town, gym);
        });
    }

    private synchronized void enqueueRctGymLeadersInChunk(ServerLevel level, int chunkX, int chunkZ) {
        towns.values().forEach(town -> {
            if (town.gymPosition() == null || town.gymLeaderPosition() == null
                    || town.gymLeaderPosition().getX() >> 4 != chunkX || town.gymLeaderPosition().getZ() >> 4 != chunkZ
                    || town.gymLeaderState() == GymLeaderState.DEGRADED || town.gymLeaderState() == GymLeaderState.UNASSIGNED) return;
            String allocationKey = town.siteX() + "," + town.siteZ() + "|town:" + town.id();
            ResourceLocation gymId = ResourceLocation.tryParse(allocations.get(allocationKey));
            GymArchetype gym = gymId == null ? null : LeagueDataReloadListener.current().gymArchetypes().get(gymId);
            if (gym != null) RctGymLeaderBridge.enqueue(level, town, gym);
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

    private double townDistanceSquared(String firstTownKey, String secondTownKey) {
        TownAnchor first = towns.get(firstTownKey);
        TownAnchor second = towns.get(secondTownKey);
        return first.routeEndpoint().distSqr(second.routeEndpoint());
    }

    private static String settlementId(ResourceLocation structure, net.minecraft.world.level.ChunkPos startChunk) {
        String path = structure.getPath();
        String prefix = structure.getNamespace().equals("bca") && path.startsWith("village/")
                ? "bca_" + path.substring("village/".length()).replace('/', '_')
                : "settlement_" + structure.getNamespace() + "_" + path.replace('/', '_');
        return prefix + "_" + startChunk.x + "_" + startChunk.z;
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
        plan.legacyCampaignId = tag.getString("campaign");
        ListTag campaignEntries = tag.getList("campaigns_by_profile", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < campaignEntries.size(); index++) {
            CompoundTag entry = campaignEntries.getCompound(index);
            plan.campaignsByProfile.put(entry.getString("profile"), entry.getString("campaign"));
            if (entry.contains("rct_series")) plan.rctSeriesByProfile.put(entry.getString("profile"), entry.getString("rct_series"));
        }
        ListTag entries = tag.getList("allocations", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            plan.allocations.put(entry.getString("key"), entry.getString("gym"));
        }
        ListTag plannedEntries = tag.getList("planned_sites", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < plannedEntries.size(); index++) {
            CompoundTag entry = plannedEntries.getCompound(index);
            plan.plannedSites.add(entry.getInt("x") + "," + entry.getInt("z"));
        }
        ListTag townEntries = tag.getList("towns", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < townEntries.size(); index++) {
            TownAnchor town = TownAnchor.load(townEntries.getCompound(index));
            plan.towns.put(townKey(town.siteX(), town.siteZ(), town.id()), town);
        }
        ListTag routeEntries = tag.getList("campaign_routes", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < routeEntries.size(); index++) {
            CampaignRoute route = CampaignRoute.load(routeEntries.getCompound(index));
            plan.campaignRoutes.put(route.id(), route);
        }
        ListTag legendaryEntries = tag.getList("legendary_claims", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < legendaryEntries.size(); index++) {
            CompoundTag entry = legendaryEntries.getCompound(index);
            plan.legendaryClaims.put(entry.getString("legendary"), entry.getString("claimant"));
        }
        ListTag rewardEntries = tag.getList("claimed_rewards", CompoundTag.TAG_STRING);
        for (int index = 0; index < rewardEntries.size(); index++) plan.claimedRewards.add(rewardEntries.getString(index));
        return plan;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        ListTag campaignEntries = new ListTag();
        campaignsByProfile.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag campaign = new CompoundTag();
            campaign.putString("profile", entry.getKey());
            campaign.putString("campaign", entry.getValue());
            String rctSeries = rctSeriesByProfile.get(entry.getKey());
            if (rctSeries != null) campaign.putString("rct_series", rctSeries);
            campaignEntries.add(campaign);
        });
        tag.put("campaigns_by_profile", campaignEntries);
        ListTag entries = new ListTag();
        allocations.forEach((key, gym) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("key", key);
            entry.putString("gym", gym);
            entries.add(entry);
        });
        tag.put("allocations", entries);
        ListTag plannedEntries = new ListTag();
        plannedSites.stream().sorted().forEach(key -> {
            String[] parts = key.split(",", 2);
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", Integer.parseInt(parts[0]));
            entry.putInt("z", Integer.parseInt(parts[1]));
            plannedEntries.add(entry);
        });
        tag.put("planned_sites", plannedEntries);
        ListTag townEntries = new ListTag();
        towns.values().stream()
                .sorted(Comparator.comparing(town -> townKey(town.siteX(), town.siteZ(), town.id())))
                .map(TownAnchor::save)
                .forEach(townEntries::add);
        tag.put("towns", townEntries);
        ListTag routeEntries = new ListTag();
        campaignRoutes.values().stream()
                .sorted(Comparator.comparing(CampaignRoute::id))
                .map(CampaignRoute::save)
                .forEach(routeEntries::add);
        tag.put("campaign_routes", routeEntries);
        ListTag legendaryEntries = new ListTag();
        legendaryClaims.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag claim = new CompoundTag();
            claim.putString("legendary", entry.getKey());
            claim.putString("claimant", entry.getValue());
            legendaryEntries.add(claim);
        });
        tag.put("legendary_claims", legendaryEntries);
        ListTag rewardEntries = new ListTag();
        claimedRewards.stream().sorted().map(StringTag::valueOf).forEach(rewardEntries::add);
        tag.put("claimed_rewards", rewardEntries);
        return tag;
    }

    private record RouteCandidate(String fromTownKey, String toTownKey, double distanceSquared)
            implements Comparable<RouteCandidate> {
        @Override
        public int compareTo(RouteCandidate other) {
            int distance = Double.compare(distanceSquared, other.distanceSquared);
            if (distance != 0) return distance;
            int from = fromTownKey.compareTo(other.fromTownKey);
            return from != 0 ? from : toTownKey.compareTo(other.toTownKey);
        }
    }
}
