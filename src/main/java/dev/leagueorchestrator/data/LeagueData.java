package dev.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable snapshot of datapack definitions. */
public record LeagueData(
        Map<ResourceLocation, GymArchetype> gymArchetypes,
        Map<ResourceLocation, Set<ResourceLocation>> speciesPools
) {
    public static final LeagueData EMPTY = new LeagueData(Map.of(), Map.of());

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
}
