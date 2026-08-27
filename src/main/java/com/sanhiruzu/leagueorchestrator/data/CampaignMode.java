package com.sanhiruzu.leagueorchestrator.data;

/** Whether a campaign names its outcomes or draws them from seeded pools. */
public enum CampaignMode {
    FIXED,
    PROCEDURAL;

    static CampaignMode parse(String value) {
        try {
            return CampaignMode.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown campaign mode '" + value + "'. Use fixed or procedural.", exception);
        }
    }
}
