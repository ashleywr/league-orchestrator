package dev.leagueorchestrator.provider;

import dev.leagueorchestrator.data.LegendaryEncounter;

/** Future bridge for one-time legendary encounter activation. */
public interface LegendaryProvider extends ProviderAdapter {
    boolean supports(LegendaryEncounter encounter);
}
