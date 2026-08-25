package dev.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/** Provider-neutral leader/trainer reference; adapters own the actual battle creation. */
public record TrainerDefinition(
        ResourceLocation id,
        String provider,
        List<ResourceLocation> teamPools,
        int minimumLevel,
        int maximumLevel,
        Set<String> requiredProviders
) {}
