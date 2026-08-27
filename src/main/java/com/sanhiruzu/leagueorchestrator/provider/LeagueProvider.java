package com.sanhiruzu.leagueorchestrator.provider;

import java.util.Set;

/** A runtime bridge capability exposed by an installed provider mod. */
public record LeagueProvider(String modId, Set<String> features) {}
