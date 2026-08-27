package dev.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * The top-level campaign contract. Content packs describe possibilities here;
 * the world plan records its chosen campaign and later seeded selections.
 */
public record CampaignDefinition(
        ResourceLocation id,
        CampaignMode mode,
        String seedSalt,
        String gymPool,
        String rctSeriesId,
        List<String> townNames,
        Set<ResourceLocation> regionProfiles,
        boolean fallback,
        int priority,
        List<ResourceLocation> townPalettes,
        List<ResourceLocation> routeProfiles,
        List<ResourceLocation> encounterProfiles,
        List<ResourceLocation> legendaryEncounters,
        List<ResourceLocation> trainers,
        List<ResourceLocation> rewards,
        Set<String> requiredProviders
) {}
