package com.sanhiruzu.leagueorchestrator.data;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementArchetypeTest {
    @Test
    void bcaTemplatePrefixMatchesAllTiersButNotOtherFamilies() {
        SettlementArchetype rule = new SettlementArchetype("bca:village/ice",
                SettlementArchetype.GymTownMode.NEVER, Set.of());

        assertTrue(rule.matchesTownId("bca_ice_small_12_-4"));
        assertTrue(rule.matchesTownId("bca_ice_large_12_-4"));
        assertFalse(rule.matchesTownId("bca_fighting_large_12_-4"));
    }
}
