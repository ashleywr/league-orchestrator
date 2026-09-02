package com.sanhiruzu.leagueorchestrator.data;

import com.google.gson.JsonObject;

/**
 * Data-driven settlement scale for one campaign.
 *
 * <p>Every distance is authored for {@link #referenceContinentDiameterBlocks}
 * and is scaled when a generator exposes a differently sized continent. Count
 * ranges scale by area; distance and search ranges scale linearly. This keeps
 * a compact Kanto campaign compact, while allowing a larger content pack to
 * opt into a denser, proportionally spaced settlement network.</p>
 */
public record SettlementLayout(
        int referenceContinentDiameterBlocks,
        Range leagueTownCount,
        Range townSpacingBlocks,
        Range coastalTownSharePermille,
        Range localSearchRadiusBlocks,
        Range coastProbeDistanceBlocks,
        Range maximumBuildHeight,
        Range maximumLocalRelief,
        Range maximumTownTerracingRelief,
        Range inlandSearchAttempts,
        Range coastalSearchAttempts,
        Range incidentalSettlementCount,
        Range incidentalSettlementSpacingBlocks,
        Range incidentalSearchAttempts,
        Range incidentalRoadEndpointSharePermille
) {
    /** Kanto Classic's current behaviour, expressed as campaign data. */
    public static final SettlementLayout KANTO_DEFAULT = new SettlementLayout(
            20_096,
            new Range(10, 10, 10),
            new Range(1_280, 2_648, 4_096),
            new Range(200, 400, 600),
            new Range(1_024, 1_760, 2_800),
            new Range(256, 448, 640),
            new Range(96, 104, 120),
            new Range(8, 12, 20),
            new Range(20, 32, 48),
            new Range(4, 4, 12),
            new Range(16, 32, 64),
            new Range(4, 8, 12),
            new Range(896, 1_440, 2_400),
            new Range(8, 16, 32),
            new Range(250, 500, 750)
    );

    public SettlementLayout {
        if (referenceContinentDiameterBlocks < 1_024) {
            throw new IllegalArgumentException("reference_continent_diameter_blocks must be at least 1024");
        }
    }

    public double scaleFor(int actualContinentDiameterBlocks) {
        return Math.max(0.25D, actualContinentDiameterBlocks / (double) referenceContinentDiameterBlocks);
    }

    public int scaledLeagueTownTarget(double scale) {
        return leagueTownCount.scaleByArea(scale);
    }

    public int scaledMinimumTownSpacing(double scale) {
        return townSpacingBlocks.scaleMinimum(scale);
    }

    public int scaledAverageTownSpacing(double scale) {
        return townSpacingBlocks.scaleAverage(scale);
    }

    public int scaledMaximumTownSpacing(double scale) {
        return townSpacingBlocks.scaleMaximum(scale);
    }

    public boolean isKantoDefault() {
        return equals(KANTO_DEFAULT);
    }

    public static SettlementLayout fromJson(JsonObject root) {
        if (root == null) return KANTO_DEFAULT;
        return new SettlementLayout(
                boundedInt(root, "reference_continent_diameter_blocks", KANTO_DEFAULT.referenceContinentDiameterBlocks,
                        1_024, 131_072),
                range(root, "league_towns", KANTO_DEFAULT.leagueTownCount, 1, 64),
                range(root, "town_spacing_blocks", KANTO_DEFAULT.townSpacingBlocks, 64, 65_536),
                range(root, "coastal_town_share_permille", KANTO_DEFAULT.coastalTownSharePermille, 0, 1_000),
                range(root, "local_search_radius_blocks", KANTO_DEFAULT.localSearchRadiusBlocks, 16, 65_536),
                range(root, "coast_probe_distance_blocks", KANTO_DEFAULT.coastProbeDistanceBlocks, 16, 16_384),
                range(root, "maximum_build_height", KANTO_DEFAULT.maximumBuildHeight, 64, 320),
                range(root, "maximum_local_relief", KANTO_DEFAULT.maximumLocalRelief, 0, 128),
                range(root, "maximum_town_terracing_relief", KANTO_DEFAULT.maximumTownTerracingRelief, 0, 192),
                range(root, "inland_search_attempts", KANTO_DEFAULT.inlandSearchAttempts, 1, 256),
                range(root, "coastal_search_attempts", KANTO_DEFAULT.coastalSearchAttempts, 1, 256),
                range(root, "incidental_settlements", KANTO_DEFAULT.incidentalSettlementCount, 0, 128),
                range(root, "incidental_spacing_blocks", KANTO_DEFAULT.incidentalSettlementSpacingBlocks, 64, 65_536),
                range(root, "incidental_search_attempts", KANTO_DEFAULT.incidentalSearchAttempts, 1, 256),
                range(root, "incidental_road_endpoint_share_permille", KANTO_DEFAULT.incidentalRoadEndpointSharePermille, 0, 1_000)
        );
    }

    private static Range range(JsonObject root, String key, Range fallback, int minimum, int maximum) {
        if (!root.has(key) || !root.get(key).isJsonObject()) return fallback;
        JsonObject value = root.getAsJsonObject(key);
        return new Range(
                boundedInt(value, "minimum", fallback.minimum, minimum, maximum),
                boundedInt(value, "average", fallback.average, minimum, maximum),
                boundedInt(value, "maximum", fallback.maximum, minimum, maximum)
        );
    }

    private static int boundedInt(JsonObject root, String key, int fallback, int minimum, int maximum) {
        if (!root.has(key)) return fallback;
        int value = root.get(key).getAsInt();
        return Math.clamp(value, minimum, maximum);
    }

    public record Range(int minimum, int average, int maximum) {
        public Range {
            if (minimum > average || average > maximum) {
                throw new IllegalArgumentException("settlement layout ranges require minimum <= average <= maximum");
            }
        }

        public int scaleMinimum(double scale) { return scaled(minimum, scale); }
        public int scaleAverage(double scale) { return scaled(average, scale); }
        public int scaleMaximum(double scale) { return scaled(maximum, scale); }

        public int scaleByArea(double scale) {
            return Math.clamp((int) Math.round(average * scale * scale), scaleMinimum(scale), scaleMaximum(scale));
        }

        private static int scaled(int value, double scale) {
            return Math.max(1, (int) Math.round(value * scale));
        }
    }
}
