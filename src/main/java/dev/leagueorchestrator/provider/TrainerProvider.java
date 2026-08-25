package dev.leagueorchestrator.provider;

import dev.leagueorchestrator.data.TrainerDefinition;

/** Future bridge for battle-engine trainer/leader instances. */
public interface TrainerProvider extends ProviderAdapter {
    boolean supports(TrainerDefinition trainer);
}
