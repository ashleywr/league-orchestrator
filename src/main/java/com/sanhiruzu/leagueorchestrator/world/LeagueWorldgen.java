package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

/** Registers only League-owned worldgen codecs; content still lives in datapacks. */
public final class LeagueWorldgen {
    public static final StructurePlacementType<LeagueTownStructurePlacement> PLANNED_TOWN = Registry.register(
            BuiltInRegistries.STRUCTURE_PLACEMENT,
            ResourceLocation.fromNamespaceAndPath(LeagueOrchestrator.MOD_ID, "planned_town"),
            () -> LeagueTownStructurePlacement.CODEC
    );

    public static final StructurePlacementType<OutsideLeaguePlacement> OUTSIDE_LEAGUE = Registry.register(
            BuiltInRegistries.STRUCTURE_PLACEMENT,
            ResourceLocation.fromNamespaceAndPath(LeagueOrchestrator.MOD_ID, "outside_league"),
            () -> OutsideLeaguePlacement.CODEC
    );

    private LeagueWorldgen() {}

    /** Forces static registration before the server decodes structure-set datapacks. */
    public static void bootstrap() {}
}
