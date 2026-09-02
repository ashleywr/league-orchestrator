package com.sanhiruzu.leagueorchestrator.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import java.util.Optional;

/**
 * Vanilla random spread everywhere except the mounted League dimension.
 *
 * <p>A campaign replaces {@code minecraft:villages} so that the campaign world
 * grows its own towns instead of vanilla villages. Once League can mount in its
 * own dimension that replacement reaches too far: it would strip villages from
 * a hub overworld as well, taking trading, raids, and villager mechanics with
 * them. The hub is supposed to be an ordinary persistent world.
 *
 * <p>So the vanilla village set keeps vanilla structures and vanilla spread,
 * and is simply switched off inside the mount, where the campaign's own town
 * set takes over.
 *
 * <p>Spread is delegated to a real {@link RandomSpreadStructurePlacement}
 * rather than reimplemented, so villages land on exactly the chunks a vanilla
 * world of the same seed would put them on. Reimplementing the maths would risk
 * a subtly different grid, which is the kind of difference nobody notices until
 * a seed no longer matches.
 */
public final class OutsideLeaguePlacement extends StructurePlacement {
    public static final MapCodec<OutsideLeaguePlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            net.minecraft.util.ExtraCodecs.POSITIVE_INT.fieldOf("spacing").forGetter(p -> p.spacing),
            net.minecraft.util.ExtraCodecs.NON_NEGATIVE_INT.fieldOf("separation").forGetter(p -> p.separation),
            RandomSpreadType.CODEC.optionalFieldOf("spread_type", RandomSpreadType.LINEAR)
                    .forGetter(p -> p.spreadType),
            com.mojang.serialization.Codec.INT.fieldOf("salt").forGetter(p -> p.salt)
    ).apply(instance, OutsideLeaguePlacement::new));

    private final int spacing;
    private final int separation;
    private final RandomSpreadType spreadType;
    private final int salt;
    private final RandomSpreadStructurePlacement delegate;

    public OutsideLeaguePlacement(int spacing, int separation, RandomSpreadType spreadType, int salt) {
        super(Vec3i.ZERO, FrequencyReductionMethod.DEFAULT, 1.0F, salt, Optional.empty());
        this.spacing = spacing;
        this.separation = separation;
        this.spreadType = spreadType;
        this.salt = salt;
        this.delegate = new RandomSpreadStructurePlacement(
                Vec3i.ZERO, FrequencyReductionMethod.DEFAULT, 1.0F, salt, Optional.empty(),
                spacing, separation, spreadType);
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        // Terra can wrap a generator state while RoadWeaver creates a
        // road-triggered chunk. Retain the level-scoped fallback only for the
        // legacy Terra-overworld layout; applying it when Kanto is a separate
        // League dimension would incorrectly switch villages off in the hub.
        if (LeagueMount.hasTerraOverworldMountedState()) {
            return false;
        }
        if (LeagueMount.isMountedState(state)) {
            return false;
        }
        // isStructureChunk applies the frequency reduction as well as the grid
        // test, and this placement is inside its own isStructureChunk, so that
        // reduction runs twice. Harmless only because the codec fixes frequency
        // at 1.0; do not expose it without splitting these apart.
        return delegate.isStructureChunk(state, chunkX, chunkZ);
    }

    @Override
    public StructurePlacementType<?> type() {
        return LeagueWorldgen.OUTSIDE_LEAGUE;
    }
}
