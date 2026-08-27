package com.sanhiruzu.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** A named encounter tier; species and level decisions remain campaign data. */
public record EncounterProfile(
        ResourceLocation id,
        ResourceLocation speciesPool,
        int minimumLevel,
        int maximumLevel,
        Set<String> contexts,
        Set<String> requiredProviders
) {}
