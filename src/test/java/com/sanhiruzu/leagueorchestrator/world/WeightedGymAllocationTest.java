package com.sanhiruzu.leagueorchestrator.world;

import com.sanhiruzu.leagueorchestrator.data.GymArchetype;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises which gym archetype a seed+key would allocate, without a
 * ServerLevel, an AtlasRegion, or any placed structure -- {@link
 * WorldLeaguePlan#weightedPick} is a pure function once given candidates.
 */
class WeightedGymAllocationTest {
    @Test
    void sameSeedAndKeyAlwaysPicksTheSameArchetype() {
        // weightedPick sorts its argument in place, so the list passed in
        // must be mutable -- List.of(...) would throw on that sort.
        List<GymArchetype> candidates = new ArrayList<>(List.of(gym("alpha", 1), gym("beta", 1), gym("gamma", 1)));

        GymArchetype first = WorldLeaguePlan.weightedPick(candidates, 12345L, "site:0,0|town:pallet");
        GymArchetype second = WorldLeaguePlan.weightedPick(candidates, 12345L, "site:0,0|town:pallet");

        assertEquals(first.id(), second.id());
    }

    @Test
    void neverPicksOutsideTheCandidateSet() {
        List<GymArchetype> candidates = new ArrayList<>(List.of(gym("alpha", 3), gym("beta", 5), gym("gamma", 2)));
        Set<ResourceLocation> allowed = Set.of(candidates.get(0).id(), candidates.get(1).id(), candidates.get(2).id());

        for (long seed = 0; seed < 500; seed++) {
            GymArchetype picked = WorldLeaguePlan.weightedPick(candidates, seed, "site:1,2|town:test-" + seed);
            assertTrue(allowed.contains(picked.id()), "picked " + picked.id() + " which is not one of the candidates");
        }
    }

    @Test
    void weightSkewsSelectionFrequencyRoughlyProportionally() {
        List<GymArchetype> candidates = new ArrayList<>(List.of(gym("common", 99), gym("rare", 1)));
        int trials = 4000;
        int commonPicks = 0;

        for (int i = 0; i < trials; i++) {
            GymArchetype picked = WorldLeaguePlan.weightedPick(candidates, 999L, "site:5,5|town:trial-" + i);
            if (picked.id().getPath().equals("common")) commonPicks++;
        }

        double share = commonPicks / (double) trials;
        // Expected ~0.99; a wide band avoids flakiness while still catching a
        // broken weighting (e.g. an accidental uniform pick would land near 0.5).
        assertTrue(share > 0.90, "expected the 99-weight candidate to dominate, got share=" + share);
    }

    private static GymArchetype gym(String path, int weight) {
        return new GymArchetype(ResourceLocation.fromNamespaceAndPath("test", path), "pool", Set.of(), weight,
                "", "", "", List.of(), null);
    }
}
