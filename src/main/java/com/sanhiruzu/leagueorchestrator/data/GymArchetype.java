package com.sanhiruzu.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.List;

/** A reusable physical gym/leader candidate supplied by a datapack. */
public record GymArchetype(
        ResourceLocation id,
        String pool,
        Set<String> regionProfiles,
        int weight,
        String structureTag,
        String trainerId,
        String badgeId,
        List<ResourceLocation> rewardIds,
        net.minecraft.core.BlockPos leaderOffset
) {
    public boolean supportsProfile(String profileId) {
        return regionProfiles.isEmpty() || regionProfiles.contains(profileId);
    }
}
