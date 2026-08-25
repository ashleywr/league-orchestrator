package dev.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** A typed reward handoff, such as currency, a TM, or a Mega Showdown item. */
public record RewardDefinition(
        ResourceLocation id,
        String provider,
        String kind,
        String value,
        int amount,
        Set<String> requiredProviders
) {}
