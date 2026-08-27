package com.sanhiruzu.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** A unique, planned encounter. Its claim state will live in WorldLeaguePlan. */
public record LegendaryEncounter(
        ResourceLocation id,
        ResourceLocation species,
        String trigger,
        Set<String> regionProfiles,
        Set<String> requiredProviders
) {}
