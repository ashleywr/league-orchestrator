package com.sanhiruzu.leagueorchestrator.provider;

import com.sanhiruzu.leagueorchestrator.data.RewardDefinition;

/** Future bridge for idempotent campaign reward grants. */
public interface RewardProvider extends ProviderAdapter {
    boolean supports(RewardDefinition reward);
}
