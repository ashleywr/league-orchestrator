package dev.leagueorchestrator.provider;

import dev.leagueorchestrator.data.TownPalette;

/** Future bridge for a town palette and its explicit path/road ports. */
public interface TownGenerationProvider extends ProviderAdapter {
    boolean supports(TownPalette palette);
}
