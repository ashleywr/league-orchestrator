package dev.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/** A reusable collection of League-owned town pools and explicitly marked ports. */
public record TownPalette(
        ResourceLocation id,
        Set<String> regionProfiles,
        String generator,
        List<ResourceLocation> structurePools,
        Set<String> portTypes,
        Set<String> requiredProviders
) {}
