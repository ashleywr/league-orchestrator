package com.sanhiruzu.leagueorchestrator.provider;

import com.sanhiruzu.leagueorchestrator.data.TownPalette;

/** Future bridge for a town palette and its explicit path/road ports. */
public interface TownGenerationProvider extends ProviderAdapter {
    boolean supports(TownPalette palette);
}
