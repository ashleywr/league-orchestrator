package com.sanhiruzu.leagueorchestrator.data;

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
        /** BCA/template families that are permitted to be ordinary or gym towns. */
        List<SettlementArchetype> settlementArchetypes,
        /** Proportional town, coast, road-endpoint, and incidental-settlement tuning. */
        SettlementLayout settlementLayout,
        /** Mods this campaign cannot run without. Missing ones make it unavailable. */
        Set<String> requiredProviders,
        /**
         * Mods that make this campaign better but are not needed for it to run.
         * Missing ones are reported once at load and change nothing else - the
         * campaign still resolves, and every feature that does not depend on
         * them still works.
         */
        Set<String> recommendedProviders
) {}
