package dev.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;

/** Immutable snapshot of datapack definitions. */
public record LeagueData(
        Map<ResourceLocation, GymArchetype> gymArchetypes,
        Map<ResourceLocation, Set<ResourceLocation>> speciesPools,
        Map<ResourceLocation, CampaignDefinition> campaigns,
        Map<ResourceLocation, TownPalette> townPalettes,
        Map<ResourceLocation, RouteProfile> routeProfiles,
        Map<ResourceLocation, EncounterProfile> encounterProfiles,
        Map<ResourceLocation, LegendaryEncounter> legendaryEncounters,
        Map<ResourceLocation, TrainerDefinition> trainers,
        Map<ResourceLocation, RewardDefinition> rewards
) {
    public static final LeagueData EMPTY = new LeagueData(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    public List<GymArchetype> gymsInPool(String pool, String profileId) {
        return gymArchetypes.values().stream()
                .filter(gym -> gym.pool().equals(pool))
                .filter(gym -> gym.supportsProfile(profileId))
                .toList();
    }

    public boolean poolContains(ResourceLocation poolId, ResourceLocation speciesId) {
        return speciesPools.getOrDefault(poolId, Set.of()).contains(speciesId);
    }

    public boolean hasSpeciesPool(ResourceLocation poolId) {
        return speciesPools.containsKey(poolId);
    }

    public Collection<GymArchetype> allGyms() {
        return gymArchetypes.values();
    }

    public Optional<CampaignDefinition> campaign(ResourceLocation id) {
        return Optional.ofNullable(campaigns.get(id));
    }

    /** Exact region declarations win; the procedural fallback covers every other profile. */
    public Optional<CampaignDefinition> campaignForProfile(ResourceLocation profileId) {
        return exactCampaignForProfile(profileId)
                .or(() -> campaigns.values().stream()
                        .filter(CampaignDefinition::fallback)
                        .sorted(campaignOrder())
                        .findFirst());
    }

    /** Returns only a content pack that explicitly claims this profile. */
    public Optional<CampaignDefinition> exactCampaignForProfile(ResourceLocation profileId) {
        return campaigns.values().stream()
                .filter(campaign -> campaign.regionProfiles().contains(profileId))
                .sorted(campaignOrder())
                .findFirst();
    }

    private static Comparator<CampaignDefinition> campaignOrder() {
        return Comparator.comparingInt(CampaignDefinition::priority).reversed()
                .thenComparing(campaign -> campaign.id().toString());
    }
}
