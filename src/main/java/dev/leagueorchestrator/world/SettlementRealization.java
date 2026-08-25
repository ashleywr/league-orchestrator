package dev.leagueorchestrator.world;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Reconciles an already-planned settlement once vanilla has made its actual
 * jigsaw bounding box available. It never discovers a new town or requests a
 * chunk; prediction is the sole source of settlement identity.
 */
public final class SettlementRealization {
    private SettlementRealization() {}

    public static void realize(ServerLevel level, LevelChunk chunk) {
        if (level != level.getServer().overworld()) return;
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        chunk.getAllStarts().forEach((structure, start) -> realizeStart(level, structures, structure, start));
    }

    private static void realizeStart(ServerLevel level, Registry<Structure> structures, Structure structure, StructureStart start) {
        if (!start.isValid()) return;
        ResourceLocation id = structures.getKey(structure);
        if (id == null || !structures.getHolder(id).map(holder -> holder.is(SettlementPredictor.SETTLEMENTS)).orElse(false)) return;
        WorldLeaguePlan.get(level).realizeSettlement(level, id, start.getChunkPos(), start.getBoundingBox())
                .ifPresent(town -> AutoTownGymPlacement.enqueue(level, town));
    }
}
