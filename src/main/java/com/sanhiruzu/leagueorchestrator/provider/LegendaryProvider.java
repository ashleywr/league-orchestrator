package com.sanhiruzu.leagueorchestrator.provider;

import com.sanhiruzu.leagueorchestrator.data.LegendaryEncounter;

/** Future bridge for one-time legendary encounter activation. */
public interface LegendaryProvider extends ProviderAdapter {
    boolean supports(LegendaryEncounter encounter);
}
