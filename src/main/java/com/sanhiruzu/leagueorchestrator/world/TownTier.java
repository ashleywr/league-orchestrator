package com.sanhiruzu.leagueorchestrator.world;

/** Semantic settlement scale used by campaign planners rather than draw weights. */
public enum TownTier {
    SMALL,
    MID,
    LARGE,
    UNKNOWN;

    public static TownTier fromSettlementId(String settlementId) {
        if (settlementId == null) return UNKNOWN;
        if (settlementId.contains("_large")) return LARGE;
        if (settlementId.contains("_mid")) return MID;
        if (settlementId.contains("_small")) return SMALL;
        return UNKNOWN;
    }
}
