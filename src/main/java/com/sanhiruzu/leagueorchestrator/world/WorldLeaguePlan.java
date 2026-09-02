package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import com.sanhiruzu.leagueorchestrator.data.GymArchetype;
import com.sanhiruzu.leagueorchestrator.data.SettlementArchetype;
import com.sanhiruzu.leagueorchestrator.data.CampaignDefinition;
import com.sanhiruzu.leagueorchestrator.data.LeagueDataReloadListener;
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
    /** Predicted BCA starts that permanently resolved as a route stop, not a town. */
    private final Map<String, BlockPos> roadsideStops = new HashMap<>();
    private final Map<String, CampaignRoute> campaignRoutes = new HashMap<>();
    private final Set<String> plannedSites = new HashSet<>();
    private final Map<String, String> legendaryClaims = new HashMap<>();
    private final Set<String> claimedRewards = new HashSet<>();
    private final Map<String, String> campaignsByProfile = new HashMap<>();
    private final Map<String, String> rctSeriesByProfile = new HashMap<>();
    /** New worlds get one League-controlled starter town; existing ledgers are never relocated. */
    private boolean starterInitialized;
    private String starterTownKey = "";
    private BlockPos starterOrigin;
    private BlockPos starterSpawn;
    private final Set<String> rejectedStarterTowns = new HashSet<>();
    /** Read once only to migrate worlds created during the global-campaign prototype. */
    private String legacyCampaignId = "";

    public static void ensureMountedPlan(MinecraftServer server) {
        for (ServerLevel level : LeagueMount.mountedLevels(server)) {
            get(level);
        }
    }

    /** Reconciles League's durable town ledger into Region Engine's generic place index. */
    public static void reconcileRegionPlaces(MinecraftServer server) {
        for (ServerLevel level : LeagueMount.mountedLevels(server)) {
            get(level).publishRegionPlaces(level);
        }
    }

    /** Re-publish the saved campaign graph after a restart when RoadWeaver is installed. */
    public static void reconcileRoadWeaverCampaign(MinecraftServer server) {
        for (ServerLevel level : LeagueMount.mountedLevels(server)) {
            get(level).publishRoadWeaverCampaign(level);
        }
    }

    /** Requeue saved gym anchors so persistent RCT leaders survive restarts and repairs. */
    public static void reconcileRctGymLeaders(MinecraftServer server) {
        for (ServerLevel level : LeagueMount.mountedLevels(server)) {
            get(level).enqueueRctGymLeaders(level);
        }
    }

    /**
     * Built-in content packs can become available after the first server data
     * snapshot. Upgrade only generated Frontier placeholder names once an
     * explicit profile campaign has arrived; authored and already-resolved
     * names remain immutable.
     */
    public static void reconcileDeferredCampaignContent(MinecraftServer server) {
        for (ServerLevel level : LeagueMount.mountedLevels(server)) {
            get(level).upgradeDeferredCampaignContent(level);
        }
    }

    /** Revisits only the newly loaded chunk; this never asks Minecraft to load terrain for an addon. */
    public static void reconcileRctGymLeadersInChunk(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (!LeagueMount.isMounted(level)) return;
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
        ResourceLocation profileId = WorldGeography.profileAt(level, position.getX(), position.getZ());
        var data = LeagueDataReloadListener.current();
        Optional<CampaignDefinition> exact = data.exactCampaignForProfile(profileId);
        String stored = campaignsByProfile.get(profileId.toString());
        if (stored != null) {
            ResourceLocation storedId = ResourceLocation.tryParse(stored);
            Optional<CampaignDefinition> campaign = storedId == null ? Optional.empty() : LeagueDataReloadListener.current().campaign(storedId);
            if (campaign.filter(CampaignDefinition::fallback).isPresent() && exact.isPresent()) {
                CampaignDefinition upgraded = exact.get();
                campaignsByProfile.put(profileId.toString(), upgraded.id().toString());
                if (!upgraded.rctSeriesId().isBlank()) rctSeriesByProfile.put(profileId.toString(), upgraded.rctSeriesId());
                setDirty();
                return Optional.of(upgraded);
            }
            campaign.filter(value -> !value.rctSeriesId().isBlank()).ifPresent(value -> {
                if (rctSeriesByProfile.putIfAbsent(profileId.toString(), value.rctSeriesId()) == null) setDirty();
            });
            return campaign;
        }

        ResourceLocation legacy = ResourceLocation.tryParse(legacyCampaignId);
        Optional<CampaignDefinition> resolved = legacy == null
                ? data.campaignForProfile(profileId)
                : exact.or(() -> data.campaign(legacy));
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
        ResourceLocation profileId = WorldGeography.profileAt(level, position.getX(), position.getZ());
        return Optional.ofNullable(rctSeriesByProfile.get(profileId.toString()));
    }

    public synchronized Optional<GymArchetype> allocate(ServerLevel level, BlockPos position, String slot, String pool) {
        return allocate(level, position, slot, pool, Set.of());
    }

    /** Allocates from a campaign pool, optionally narrowed by a town-template rule. */
    private synchronized Optional<GymArchetype> allocate(ServerLevel level, BlockPos position, String slot, String pool,
                                                          Set<ResourceLocation> permittedGymArchetypes) {
        WorldGeography.Site atlas = WorldGeography.siteAt(level, position.getX(), position.getZ());
        if (atlas.kind() == WorldGeography.Kind.OCEAN) {
            return Optional.empty();
        }

        String site = atlas.cellX() + "," + atlas.cellZ();
        String allocationKey = site + "|" + slot;
        String existing = allocations.get(allocationKey);
        if (existing != null) {
            return Optional.ofNullable(LeagueDataReloadListener.current().gymArchetypes()
                    .get(net.minecraft.resources.ResourceLocation.tryParse(existing)));
        }

        String profileId = WorldGeography.profileForSite(level, level.getSeed(), atlas.cellX(), atlas.cellZ()).toString();
        List<GymArchetype> candidates = new ArrayList<>(LeagueDataReloadListener.current().gymsInPool(pool, profileId));
        if (!permittedGymArchetypes.isEmpty()) {
            candidates.removeIf(gym -> !permittedGymArchetypes.contains(gym.id()));
        }
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
        WorldGeography.Site atlas = WorldGeography.siteAt(level, position.getX(), position.getZ());
        return Optional.ofNullable(allocations.get(atlas.cellX() + "," + atlas.cellZ() + "|" + slot));
    }

    public synchronized Optional<TownAnchor> registerTown(ServerLevel level, BlockPos center, String townId) {
        WorldGeography.Site atlas = WorldGeography.siteAt(level, center.getX(), center.getZ());
        if (atlas.kind() != WorldGeography.Kind.CONTINENT) return Optional.empty();
        String key = townKey(atlas.cellX(), atlas.cellZ(), townId);
        TownAnchor existing = towns.get(key);
        if (existing != null) return Optional.of(existing);

        TownAnchor town = new TownAnchor(townId, atlas.cellX(), atlas.cellZ(), center, null, null, center,
                null, null, GymLeaderState.UNASSIGNED, "", townName(level, townId, center));
        towns.put(key, town);
        setDirty();
        publishRegionPlace(level, town, false, false);
        RoadWeaverBridge.publishTown(level, town);
        return Optional.of(town);
    }

    public synchronized Optional<TownAnchor> townAt(ServerLevel level, BlockPos position, String townId) {
        WorldGeography.Site atlas = WorldGeography.siteAt(level, position.getX(), position.getZ());
        return Optional.ofNullable(towns.get(townKey(atlas.cellX(), atlas.cellZ(), townId)));
    }

    public synchronized Optional<TownAnchor> nearestTown(ServerLevel level, BlockPos position, double maximumDistance) {
        WorldGeography.Site atlas = WorldGeography.siteAt(level, position.getX(), position.getZ());
        double maximumDistanceSquared = maximumDistance * maximumDistance;
        return towns.values().stream()
                .filter(town -> town.siteX() == atlas.cellX() && town.siteZ() == atlas.cellZ())
                .filter(town -> town.bounds() != null)
                .filter(town -> town.center().distSqr(position) <= maximumDistanceSquared)
                .min(Comparator.comparingDouble(town -> town.center().distSqr(position)));
    }

    /**
     * Opens the starter-town flow only for a brand-new League ledger. This is
     * called before the first spawn-site prediction, so upgrading an explored
     * world cannot silently move its shared spawn.
     */
    public synchronized boolean beginStarterTown(BlockPos origin) {
        if (starterInitialized) return starterOrigin != null && starterSpawn == null;
        starterInitialized = true;
        if (!towns.isEmpty() || !plannedSites.isEmpty()) {
            setDirty();
            return false;
        }
        starterOrigin = origin.immutable();
        setDirty();
        return true;
    }

    /** Selects the closest still-viable predicted town in the initial atlas site. */
    public synchronized Optional<TownAnchor> reserveNearestStarterTown(ServerLevel level) {
        if (!starterInitialized || starterOrigin == null || starterSpawn != null) return Optional.empty();
        if (!starterTownKey.isBlank()) return Optional.ofNullable(towns.get(starterTownKey));

        WorldGeography.Site originSite = WorldGeography.siteAt(level, starterOrigin.getX(), starterOrigin.getZ());
        Optional<Map.Entry<String, TownAnchor>> selected = towns.entrySet().stream()
                .filter(entry -> entry.getValue().siteX() == originSite.cellX() && entry.getValue().siteZ() == originSite.cellZ())
                .filter(entry -> !roadsideStops.containsKey(entry.getKey()))
                .filter(entry -> !rejectedStarterTowns.contains(entry.getKey()))
                .min(Comparator.comparingDouble((Map.Entry<String, TownAnchor> entry) -> entry.getValue().center().distSqr(starterOrigin))
                        .thenComparing(Map.Entry::getKey));
        if (selected.isEmpty()) return Optional.empty();
        starterTownKey = selected.get().getKey();
        setDirty();
        return Optional.of(selected.get().getValue());
    }

    public synchronized boolean isStarterSpawnPending(TownAnchor town) {
        return starterInitialized && starterSpawn == null && starterTownKey.equals(townKey(town.siteX(), town.siteZ(), town.id()));
    }

    /** True for the durable campaign starter, including after its spawn apron is complete. */
    public synchronized boolean isStarterTown(TownAnchor town) {
        return town != null && !starterTownKey.isBlank()
                && starterTownKey.equals(townKey(town.siteX(), town.siteZ(), town.id()));
    }

    /** Gives a rejected BCA candidate no further starter priority, then permits a deterministic fallback. */
    public synchronized boolean rejectStarterTown(TownAnchor town) {
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        if (!isStarterSpawnPending(town)) return false;
        rejectedStarterTowns.add(key);
        starterTownKey = "";
        setDirty();
        return true;
    }

    /** BCA rejected this predicted start during the new-world bootstrap. */
    public synchronized boolean wasStarterTownRejected(TownAnchor town) {
        return rejectedStarterTowns.contains(townKey(town.siteX(), town.siteZ(), town.id()));
    }

    /** Persists the actual safe apron used as both world spawn and RoadWeaver port. */
    public synchronized Optional<TownAnchor> completeStarterSpawn(TownAnchor town, BlockPos spawn) {
        if (!isStarterSpawnPending(town)) return Optional.empty();
        TownAnchor updated = town.withRoadEndpoint(spawn.immutable());
        towns.put(starterTownKey, updated);
        starterSpawn = spawn.immutable();
        setDirty();
        return Optional.of(updated);
    }

    /** Records a deterministic structure-start prediction without loading a chunk or consulting a heightmap. */
    public synchronized Optional<TownAnchor> planSettlement(ServerLevel level, ResourceLocation structure, BlockPos predictedStart) {
        WorldGeography.Site atlas = WorldGeography.siteAt(level, predictedStart.getX(), predictedStart.getZ());
        if (atlas.kind() != WorldGeography.Kind.CONTINENT) return Optional.empty();
        String townId = settlementId(structure, new net.minecraft.world.level.ChunkPos(predictedStart));
        String key = townKey(atlas.cellX(), atlas.cellZ(), townId);
        TownAnchor existing = towns.get(key);
        if (existing != null) return Optional.of(existing);

        TownAnchor town = new TownAnchor(townId, atlas.cellX(), atlas.cellZ(),
                new BlockPos(predictedStart.getX(), level.getMinBuildHeight(), predictedStart.getZ()), null, null,
                new BlockPos(predictedStart.getX(), level.getMinBuildHeight(), predictedStart.getZ()), null, null,
                GymLeaderState.UNASSIGNED, "", townName(level, townId, predictedStart));
        towns.put(key, town);
        setDirty();
        publishRegionPlace(level, town, false, false);
        return Optional.of(town);
    }

    /**
     * Confirms an actual generated settlement. A prediction is only a
     * non-authoritative candidate: third-party jigsaws may reject it after
     * terrain-sensitive checks or another tagged structure may generate at a
     * nearby candidate. In that case, record the observed start so gyms and
     * other structure-dependent content follow the real town.
     */
    public synchronized Optional<TownAnchor> realizeSettlement(ServerLevel level, ResourceLocation structure,
                                                                 net.minecraft.world.level.ChunkPos startChunk, BoundingBox bounds) {
        String townId = settlementId(structure, startChunk);
        int centerX = (bounds.minX() + bounds.maxX()) / 2;
        int centerY = (bounds.minY() + bounds.maxY()) / 2;
        int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
        WorldGeography.Site atlas = WorldGeography.siteAt(level, centerX, centerZ);
        TownAnchor existing = towns.get(townKey(atlas.cellX(), atlas.cellZ(), townId));
        if (existing != null && existing.bounds() != null) return Optional.of(existing);

        TownAnchor realized = existing == null
                ? new TownAnchor(townId, atlas.cellX(), atlas.cellZ(), new BlockPos(centerX, centerY, centerZ), bounds,
                null, new BlockPos(centerX, centerY, centerZ), null, null, GymLeaderState.UNASSIGNED, "", townName(level, townId, new BlockPos(centerX, centerY, centerZ)))
                : existing.withRealizedBounds(new BlockPos(centerX, centerY, centerZ), bounds);
        if (realized.name().isBlank()) realized = realized.withName(townName(level, townId, realized.center()));
        towns.put(townKey(realized.siteX(), realized.siteZ(), realized.id()), realized);
        setDirty();
        ensureSiteRoadGraph(realized.siteX(), realized.siteZ());
        publishRegionPlace(level, realized, true, false);
        publishRoadWeaverCampaign(level);
        return Optional.of(realized);
    }

    /**
     * Returns predicted starts whose authoritative start chunk has just
     * loaded. A caller may mark only a chunk with no League settlement start
     * as a roadside stop; that makes a terrain-sensitive BCA rejection
     * durable rather than leaving a phantom town on a road terminus.
     */
    public synchronized List<TownAnchor> pendingTownStartsInChunk(int chunkX, int chunkZ) {
        return towns.entrySet().stream()
                .filter(entry -> entry.getValue().bounds() == null)
                .filter(entry -> !roadsideStops.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(town -> (town.center().getX() >> 4) == chunkX && (town.center().getZ() >> 4) == chunkZ)
                .toList();
    }

    /** Records the physical marker chosen for a rejected town start. Idempotent per town. */
    public synchronized boolean markRoadsideStop(ServerLevel level, TownAnchor town, BlockPos marker) {
        if (town == null || marker == null) return false;
        String key = townKey(town.siteX(), town.siteZ(), town.id());
        if (!towns.containsKey(key) || roadsideStops.containsKey(key)) return false;
        roadsideStops.put(key, marker.immutable());
        setDirty();
        publishRegionPlace(level, town, false, true);
        return true;
    }

    public synchronized boolean isRoadsideStop(TownAnchor town) {
        return town != null && roadsideStops.containsKey(townKey(town.siteX(), town.siteZ(), town.id()));
    }

    public synchronized boolean isSitePlanned(int siteX, int siteZ) {
        return plannedSites.contains(siteX + "," + siteZ);
    }

    /** Snapshot for bounded Minecraft-owned town-start warmup after a restart. */
    public synchronized List<TownAnchor> unrealizedTowns() {
        return towns.entrySet().stream()
                .filter(entry -> entry.getValue().bounds() == null)
                .filter(entry -> !roadsideStops.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    public synchronized void markSitePlanned(int siteX, int siteZ) {
        if (plannedSites.add(siteX + "," + siteZ)) setDirty();
    }

    /**
     * Builds the initial, sparse road topology for realized towns in one atlas site.
     * It is a deterministic minimum-distance tree rather than a level order:
     * every town is reachable, but the graph leaves room for future planned
     * shortcuts, caves, ferries, and gates to add non-linear progression. The
     * tree shape itself is computed by {@link RoadGraphPlanner}, which touches
     * no world state and can be exercised directly in a test.
     */
    public synchronized void ensureSiteRoadGraph(int siteX, int siteZ) {
        Map<String, BlockPos> sitePositions = towns.entrySet().stream()
                .filter(entry -> entry.getValue().siteX() == siteX && entry.getValue().siteZ() == siteZ)
                .filter(entry -> entry.getValue().bounds() != null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().routeEndpoint()));
        for (CampaignRoute route : RoadGraphPlanner.minimumSpanningTree(sitePositions)) {
            if (campaignRoutes.putIfAbsent(route.id(), route) == null) setDirty();
        }
    }

    public synchronized Optional<GymArchetype> gymForTown(ServerLevel level, TownAnchor town, String pool) {
        Optional<CampaignDefinition> campaign = campaignFor(level, town.center());
        Optional<SettlementArchetype> templateRule = campaign
                .flatMap(value -> value.settlementArchetypes().stream()
                        .filter(rule -> rule.matchesTownId(town.id()))
                        .findFirst());
        if (templateRule.map(SettlementArchetype::gymTownMode)
                .filter(mode -> mode == SettlementArchetype.GymTownMode.NEVER).isPresent()) {
            return Optional.empty();
        }
        Set<ResourceLocation> permitted = templateRule.map(SettlementArchetype::permittedGymArchetypes).orElse(Set.of());
        return allocate(level, town.center(), "town:" + town.id(), pool, permitted);
    }

    /** Required archetypes are allocated first; optional towns fill remaining badges. */
    public synchronized int gymTownPriority(ServerLevel level, TownAnchor town) {
        return campaignFor(level, town.center())
                .flatMap(campaign -> campaign.settlementArchetypes().stream()
                        .filter(rule -> rule.matchesTownId(town.id()))
                        .findFirst())
                .map(rule -> switch (rule.gymTownMode()) {
                    case REQUIRED -> 2;
                    case OPTIONAL -> 1;
                    case NEVER -> 0;
                }).orElse(1);
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

    /** Publishes only realized town endpoints and edges to RoadWeaver. */
    public synchronized void publishRoadWeaverCampaign(ServerLevel level) {
        towns.values().stream()
                .filter(town -> town.bounds() != null)
                .filter(town -> !isStarterSpawnPending(town))
                .forEach(town -> RoadWeaverBridge.publishTown(level, town));
        campaignRoutes.values().stream().sorted(Comparator.comparing(CampaignRoute::id)).forEach(route -> {
            TownAnchor from = towns.get(route.fromTownKey());
            TownAnchor to = towns.get(route.toTownKey());
            if (from != null && from.bounds() != null && !isStarterSpawnPending(from)
                    && to != null && to.bounds() != null && !isStarterSpawnPending(to)) {
                RoadWeaverBridge.connectCampaignRoute(level, route.id(), from, to);
            }
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

    /**
     * Creates a deterministic content-pack display name once, then stores it
     * in the town ledger. The identifier remains the only gameplay key.
     */
    private String townName(ServerLevel level, String townId, BlockPos center) {
        Optional<CampaignDefinition> campaign = campaignFor(level, center);
        List<String> candidates = campaign.map(CampaignDefinition::townNames).orElse(List.of());
        String fallback = "Frontier " + (Math.abs((townId + ":" + center.getX() + ":" + center.getZ()).hashCode()) % 10_000);
        String scope = campaign.map(value -> value.seedSalt() + "|" + townId + "|" + center.getX() + "," + center.getZ())
                .orElse(townId + "|" + center.getX() + "," + center.getZ());
        return WorldGeography.chooseName(level.getSeed(), scope, candidates, fallback);
    }

    private synchronized void upgradeDeferredCampaignContent(ServerLevel level) {
        boolean changed = false;
        for (Map.Entry<String, TownAnchor> entry : towns.entrySet()) {
            TownAnchor town = entry.getValue();
            if (!isGeneratedFrontierPlaceholder(town.name())) continue;
            Optional<CampaignDefinition> campaign = campaignFor(level, town.center());
            if (campaign.isEmpty() || campaign.get().fallback()) continue;
            entry.setValue(town.withName(townName(level, town.id(), town.center())));
            changed = true;
        }
        if (changed) {
            setDirty();
            publishRegionPlaces(level);
            LeagueOrchestrator.LOGGER.info("Resolved deferred League town names from newly available content-pack data.");
        }
    }

    private static boolean isGeneratedFrontierPlaceholder(String name) {
        return name != null && name.matches("Frontier \\d{1,4}");
    }

    private void publishRegionPlaces(ServerLevel level) {
        towns.forEach((key, town) -> {
            TownAnchor named = town;
            if (town.name().isBlank()) {
                named = town.withName(townName(level, town.id(), town.center()));
                towns.put(key, named);
                setDirty();
            }
            if (roadsideStops.containsKey(key)) {
                publishRegionPlace(level, named, false, true);
            } else {
                publishRegionPlace(level, named, named.bounds() != null, false);
            }
        });
    }

    private void publishRegionPlace(ServerLevel level, TownAnchor town, boolean realized, boolean fallback) {
        WorldGeography.publishPlace(level, town, realized, fallback);
    }

    private static String townKey(int siteX, int siteZ, String townId) {
        return siteX + "," + siteZ + "|" + townId;
    }

    private static String settlementId(ResourceLocation structure, net.minecraft.world.level.ChunkPos startChunk) {
        String path = structure.getPath();
        String prefix = structure.getNamespace().equals("bca") && path.startsWith("village/")
                ? "bca_" + path.substring("village/".length()).replace('/', '_')
                : "settlement_" + structure.getNamespace() + "_" + path.replace('/', '_');
        return prefix + "_" + startChunk.x + "_" + startChunk.z;
    }

    /** Package-visible (not private) so a pure-logic test can exercise allocation shape directly. */
    static GymArchetype weightedPick(List<GymArchetype> candidates, long worldSeed, String allocationKey) {
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
        ListTag stopEntries = tag.getList("roadside_stops", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < stopEntries.size(); index++) {
            CompoundTag entry = stopEntries.getCompound(index);
            if (entry.contains("town") && entry.contains("marker")) {
                plan.roadsideStops.put(entry.getString("town"), BlockPos.of(entry.getLong("marker")));
            }
        }
        if (tag.contains("starter_initialized")) {
            plan.starterInitialized = tag.getBoolean("starter_initialized");
            plan.starterTownKey = tag.getString("starter_town");
            plan.starterOrigin = tag.contains("starter_origin") ? BlockPos.of(tag.getLong("starter_origin")) : null;
            plan.starterSpawn = tag.contains("starter_spawn") ? BlockPos.of(tag.getLong("starter_spawn")) : null;
            ListTag rejected = tag.getList("rejected_starter_towns", CompoundTag.TAG_STRING);
            for (int index = 0; index < rejected.size(); index++) plan.rejectedStarterTowns.add(rejected.getString(index));
        } else if (!plan.towns.isEmpty() || !plan.plannedSites.isEmpty()) {
            // A ledger from before starter towns has already made worldgen
            // decisions. Preserve its existing shared spawn on upgrade.
            plan.starterInitialized = true;
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
        tag.putBoolean("starter_initialized", starterInitialized);
        if (!starterTownKey.isBlank()) tag.putString("starter_town", starterTownKey);
        if (starterOrigin != null) tag.putLong("starter_origin", starterOrigin.asLong());
        if (starterSpawn != null) tag.putLong("starter_spawn", starterSpawn.asLong());
        ListTag rejectedStarterEntries = new ListTag();
        rejectedStarterTowns.stream().sorted().map(StringTag::valueOf).forEach(rejectedStarterEntries::add);
        tag.put("rejected_starter_towns", rejectedStarterEntries);
        ListTag townEntries = new ListTag();
        towns.values().stream()
                .sorted(Comparator.comparing(town -> townKey(town.siteX(), town.siteZ(), town.id())))
                .map(TownAnchor::save)
                .forEach(townEntries::add);
        tag.put("towns", townEntries);
        ListTag roadsideStopEntries = new ListTag();
        roadsideStops.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag stop = new CompoundTag();
            stop.putString("town", entry.getKey());
            stop.putLong("marker", entry.getValue().asLong());
            roadsideStopEntries.add(stop);
        });
        tag.put("roadside_stops", roadsideStopEntries);
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
}
