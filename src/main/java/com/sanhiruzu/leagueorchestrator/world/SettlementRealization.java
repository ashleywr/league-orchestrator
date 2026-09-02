package com.sanhiruzu.leagueorchestrator.world;

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
        if (!LeagueMount.isMounted(level)) return;
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        boolean hasSettlementStart = chunk.getAllStarts().entrySet().stream()
                .anyMatch(entry -> isSettlementStart(structures, entry.getKey(), entry.getValue()));
        chunk.getAllStarts().forEach((structure, start) -> realizeStart(level, structures, structure, start));
        if (!hasSettlementStart) {
            // This event is raised during the chunk upgrade itself.  The
            // rejection path must remain non-loading; any optional marker is
            // deferred by StarterTownBootstrap.
            WorldLeaguePlan.get(level).pendingTownStartsInChunk(chunk.getPos().x, chunk.getPos().z)
                    .forEach(town -> StarterTownBootstrap.onTownRejected(level, town));
        }
    }

    private static boolean isSettlementStart(Registry<Structure> structures, Structure structure, StructureStart start) {
        if (!start.isValid()) return false;
        ResourceLocation id = structures.getKey(structure);
        return id != null && structures.getHolder(id).map(holder -> holder.is(SettlementPredictor.SETTLEMENTS)).orElse(false);
    }

    private static void realizeStart(ServerLevel level, Registry<Structure> structures, Structure structure, StructureStart start) {
        if (!start.isValid()) return;
        ResourceLocation id = structures.getKey(structure);
        if (id == null || !structures.getHolder(id).map(holder -> holder.is(SettlementPredictor.SETTLEMENTS)).orElse(false)) return;
        WorldLeaguePlan.get(level).realizeSettlement(level, id, start.getChunkPos(), start.getBoundingBox())
                .ifPresent(town -> {
                    StarterTownBootstrap.onTownRealized(level, town);
                    AutoTownGymPlacement.enqueue(level, town);
                });
    }
}
