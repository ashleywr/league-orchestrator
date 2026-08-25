package dev.leagueorchestrator.provider;

import java.util.Set;

/**
 * Implemented by an integration bridge. Adapters are intentionally small:
 * campaign data selects a provider by mod ID while the adapter owns provider
 * APIs and versions. All provider calls must go through
 * {@link ProviderIntegrationCoordinator}; adapters must never touch a
 * provider API from its preparation worker.
 */
public interface ProviderAdapter {
    String modId();

    Set<String> features();

    default boolean isAvailable() {
        return ProviderRegistry.isInstalled(modId());
    }
}
