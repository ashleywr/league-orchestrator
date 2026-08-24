package dev.leagueorchestrator.world;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Observes completed BCA village starts after they reach a server chunk. This
 * is intentionally an event listener, not a mixin in worldgen: structure
 * generation can run off-thread, while saved league data must be written on
 * the server thread only after a start and its bounding box are authoritative.
 */
public final class BcaTownDiscovery {
    private BcaTownDiscovery() {}

    public static void discover(ServerLevel level, LevelChunk chunk) {
        if (level != level.getServer().overworld()) return;
        Registry<Structure> structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        chunk.getAllStarts().forEach((structure, start) -> discoverStart(level, structures, structure, start));
    }

    private static void discoverStart(ServerLevel level, Registry<Structure> structures, Structure structure, StructureStart start) {
        if (!start.isValid()) return;
        ResourceLocation id = structures.getKey(structure);
        if (id == null || !id.getNamespace().equals("bca") || !id.getPath().startsWith("village/")) return;

        String townId = "bca_" + id.getPath().replace('/', '_')
                + "_" + start.getChunkPos().x + "_" + start.getChunkPos().z;
        WorldLeaguePlan.get(level).discoverTown(level, townId, start.getBoundingBox())
                .ifPresent(town -> AutoTownGymPlacement.enqueue(level, town));
    }
}
