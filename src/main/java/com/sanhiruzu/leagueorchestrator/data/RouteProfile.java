package com.sanhiruzu.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** Visual, encounter, and progression metadata for a planned route edge. */
public record RouteProfile(
        ResourceLocation id,
        Set<String> regionProfiles,
        String renderer,
        String portType,
        int minimumLevel,
        int maximumLevel,
        ResourceLocation encounterProfile,
        Set<String> requiredProviders
) {}
