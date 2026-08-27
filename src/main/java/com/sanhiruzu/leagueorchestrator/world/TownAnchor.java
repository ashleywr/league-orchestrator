package com.sanhiruzu.leagueorchestrator.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.UUID;

/**
 * A planned or realized settlement focal point inside one deterministic atlas
 * site. The historical type name is retained for saved-world compatibility.
 */
public record TownAnchor(String id, int siteX, int siteZ, BlockPos center, BoundingBox bounds, BlockPos gymPosition,
                         BlockPos roadEndpoint, BlockPos gymLeaderPosition, UUID gymLeaderUuid,
                         GymLeaderState gymLeaderState, String gymLeaderFailure, String name) {
    public TownAnchor withGymPosition(BlockPos position, BlockPos leaderPosition, boolean leaderRequested) {
        GymLeaderState state = leaderPosition == null || !leaderRequested ? GymLeaderState.UNASSIGNED : GymLeaderState.PENDING;
        return new TownAnchor(id, siteX, siteZ, center, bounds, position, roadEndpoint, leaderPosition, null, state, "", name);
    }

    public TownAnchor withRealizedBounds(BlockPos realizedCenter, BoundingBox realizedBounds) {
        return new TownAnchor(id, siteX, siteZ, realizedCenter, realizedBounds, gymPosition, roadEndpoint,
                gymLeaderPosition, gymLeaderUuid, gymLeaderState, gymLeaderFailure, name);
    }

    /** Keeps the town identity and generated bounds while moving its road-facing port. */
    public TownAnchor withRoadEndpoint(BlockPos endpoint) {
        return new TownAnchor(id, siteX, siteZ, center, bounds, gymPosition, endpoint,
                gymLeaderPosition, gymLeaderUuid, gymLeaderState, gymLeaderFailure, name);
    }

    public TownAnchor withGymLeaderUuid(UUID leaderUuid) {
        return new TownAnchor(id, siteX, siteZ, center, bounds, gymPosition, roadEndpoint, gymLeaderPosition,
                leaderUuid, GymLeaderState.ACTIVE, "", name);
    }

    public TownAnchor withGymLeaderState(GymLeaderState state, String failure) {
        UUID uuid = state == GymLeaderState.ACTIVE ? gymLeaderUuid : null;
        return new TownAnchor(id, siteX, siteZ, center, bounds, gymPosition, roadEndpoint, gymLeaderPosition,
                uuid, state, failure == null ? "" : failure, name);
    }

    public TownAnchor withName(String displayName) {
        return new TownAnchor(id, siteX, siteZ, center, bounds, gymPosition, roadEndpoint, gymLeaderPosition,
                gymLeaderUuid, gymLeaderState, gymLeaderFailure, displayName == null ? "" : displayName);
    }

    /** The immutable, pre-generation endpoint used by the campaign road graph. */
    public BlockPos routeEndpoint() {
        return roadEndpoint == null ? center : roadEndpoint;
    }

    /** Stable semantic scale inferred from the selected content-pack structure. */
    public TownTier tier() {
        return TownTier.fromSettlementId(id);
    }

    public boolean contains(BlockPos position) {
        return bounds != null
                && position.getX() >= bounds.minX() && position.getX() <= bounds.maxX()
                && position.getY() >= bounds.minY() && position.getY() <= bounds.maxY()
                && position.getZ() >= bounds.minZ() && position.getZ() <= bounds.maxZ();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        if (!name.isBlank()) tag.putString("name", name);
        tag.putInt("site_x", siteX);
        tag.putInt("site_z", siteZ);
        tag.putLong("center", center.asLong());
        tag.putLong("road_endpoint", routeEndpoint().asLong());
        if (bounds != null) {
            tag.putInt("min_x", bounds.minX());
            tag.putInt("min_y", bounds.minY());
            tag.putInt("min_z", bounds.minZ());
            tag.putInt("max_x", bounds.maxX());
            tag.putInt("max_y", bounds.maxY());
            tag.putInt("max_z", bounds.maxZ());
        }
        if (gymPosition != null) tag.putLong("gym_position", gymPosition.asLong());
        if (gymLeaderPosition != null) tag.putLong("gym_leader_position", gymLeaderPosition.asLong());
        if (gymLeaderUuid != null) tag.putUUID("gym_leader_uuid", gymLeaderUuid);
        tag.putString("gym_leader_state", gymLeaderState.name());
        if (!gymLeaderFailure.isBlank()) tag.putString("gym_leader_failure", gymLeaderFailure);
        return tag;
    }

    public static TownAnchor load(CompoundTag tag) {
        BlockPos gymPosition = tag.contains("gym_position")
                ? BlockPos.of(tag.getLong("gym_position"))
                : null;
        BoundingBox bounds = tag.contains("min_x")
                ? new BoundingBox(
                        tag.getInt("min_x"), tag.getInt("min_y"), tag.getInt("min_z"),
                        tag.getInt("max_x"), tag.getInt("max_y"), tag.getInt("max_z")
                )
                : null;
        BlockPos leaderPosition = tag.contains("gym_leader_position") ? BlockPos.of(tag.getLong("gym_leader_position")) : null;
        UUID leaderUuid = tag.hasUUID("gym_leader_uuid") ? tag.getUUID("gym_leader_uuid") : null;
        return new TownAnchor(
                tag.getString("id"),
                tag.getInt("site_x"),
                tag.getInt("site_z"),
                BlockPos.of(tag.getLong("center")),
                bounds,
                gymPosition,
                tag.contains("road_endpoint") ? BlockPos.of(tag.getLong("road_endpoint")) : BlockPos.of(tag.getLong("center")),
                leaderPosition,
                leaderUuid,
                GymLeaderState.fromSaved(tag.getString("gym_leader_state"), leaderPosition != null, leaderUuid != null),
                tag.getString("gym_leader_failure"),
                tag.getString("name")
        );
    }
}
