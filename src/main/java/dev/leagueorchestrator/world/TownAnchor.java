package dev.leagueorchestrator.world;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A named settlement focal point inside one deterministic atlas site. Towns
 * are deliberately server-owned data, rather than inferred from a particular
 * structure mod, so any village, custom build, or future detector can register
 * the same kind of anchor.
 */
public record TownAnchor(String id, int siteX, int siteZ, BlockPos center, BoundingBox bounds, BlockPos gymPosition) {
    public TownAnchor withGymPosition(BlockPos position) {
        return new TownAnchor(id, siteX, siteZ, center, bounds, position);
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
        tag.putInt("site_x", siteX);
        tag.putInt("site_z", siteZ);
        tag.putLong("center", center.asLong());
        if (bounds != null) {
            tag.putInt("min_x", bounds.minX());
            tag.putInt("min_y", bounds.minY());
            tag.putInt("min_z", bounds.minZ());
            tag.putInt("max_x", bounds.maxX());
            tag.putInt("max_y", bounds.maxY());
            tag.putInt("max_z", bounds.maxZ());
        }
        if (gymPosition != null) tag.putLong("gym_position", gymPosition.asLong());
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
        return new TownAnchor(
                tag.getString("id"),
                tag.getInt("site_x"),
                tag.getInt("site_z"),
                BlockPos.of(tag.getLong("center")),
                bounds,
                gymPosition
        );
    }
}
