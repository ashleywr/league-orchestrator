package com.sanhiruzu.leagueorchestrator.provider;

import net.fabricmc.loader.api.FabricLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Central inventory of provider capabilities. It deliberately names mod IDs,
 * not classes, so content validation never loads a provider's implementation.
 */
public final class ProviderRegistry {
    private static final Map<String, LeagueProvider> KNOWN = Map.ofEntries(
            Map.entry("rctapi", new LeagueProvider("rctapi", Set.of("trainer-api"))),
            Map.entry("rctmod", new LeagueProvider("rctmod", Set.of("trainers", "series"))),
            Map.entry("moogs_structures", new LeagueProvider("moogs_structures", Set.of("town-generation", "jigsaw-markers"))),
            Map.entry("cobblefurnies", new LeagueProvider("cobblefurnies", Set.of("town-decor"))),
            Map.entry("mega_showdown", new LeagueProvider("mega_showdown", Set.of("mega-rewards"))),
            Map.entry("cobbledollars", new LeagueProvider("cobbledollars", Set.of("currency-rewards"))),
            Map.entry("simpletms", new LeagueProvider("simpletms", Set.of("tm-rewards")))
    );

    private ProviderRegistry() {}

    public static boolean isInstalled(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static Set<String> missing(Set<String> requirements) {
        return requirements.stream().filter(requirement -> !isInstalled(requirement)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static Map<String, LeagueProvider> installedKnownProviders() {
        Map<String, LeagueProvider> installed = new LinkedHashMap<>();
        KNOWN.forEach((modId, provider) -> {
            if (isInstalled(modId)) installed.put(modId, provider);
        });
        return Map.copyOf(installed);
    }
}
