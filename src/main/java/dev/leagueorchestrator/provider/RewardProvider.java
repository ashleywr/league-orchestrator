package dev.leagueorchestrator.provider;

import dev.leagueorchestrator.data.RewardDefinition;

/** Future bridge for idempotent campaign reward grants. */
public interface RewardProvider extends ProviderAdapter {
    boolean supports(RewardDefinition reward);
}
