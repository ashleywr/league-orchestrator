package com.sanhiruzu.leagueorchestrator.data;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * A datapack rule for a settlement template family (for example
 * {@code bca:village/fighting}).  It deliberately describes presentation and
 * league eligibility separately: a fighting-looking BCA village does not
 * automatically become a Fighting gym unless the campaign elects it.
 */
public record SettlementArchetype(
        String structurePrefix,
        GymTownMode gymTownMode,
        Set<ResourceLocation> permittedGymArchetypes
) {
    public SettlementArchetype {
        if (structurePrefix == null || structurePrefix.isBlank()) {
            throw new IllegalArgumentException("Settlement archetype needs a structure_prefix");
        }
        gymTownMode = gymTownMode == null ? GymTownMode.OPTIONAL : gymTownMode;
        permittedGymArchetypes = permittedGymArchetypes == null ? Set.of() : Set.copyOf(permittedGymArchetypes);
    }

    /** Matches the durable town ID, so the rule also works after reloads and restarts. */
    public boolean matchesTownId(String townId) {
        return townId != null && townId.startsWith(normalizedTownIdPrefix());
    }

    public String normalizedTownIdPrefix() {
        String namespace = "";
        String path = structurePrefix;
        int separator = structurePrefix.indexOf(':');
        if (separator >= 0) {
            namespace = structurePrefix.substring(0, separator);
            path = structurePrefix.substring(separator + 1);
        }
        if (namespace.equals("bca") && path.startsWith("village/")) {
            return "bca_" + path.substring("village/".length()).replace('/', '_');
        }
        return "settlement_" + namespace + "_" + path.replace('/', '_');
    }

    public enum GymTownMode {
        NEVER,
        OPTIONAL,
        REQUIRED;

        public static GymTownMode parse(String value) {
            if (value == null || value.isBlank()) return OPTIONAL;
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "never", "none", "no" -> NEVER;
                case "optional", "eligible", "yes" -> OPTIONAL;
                case "required", "priority" -> REQUIRED;
                default -> throw new IllegalArgumentException("Unknown settlement gym mode '" + value + "'");
            };
        }
    }
}
