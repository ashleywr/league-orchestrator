package dev.leagueorchestrator.world;

/** Durable lifecycle state for a provider-owned gym leader. */
public enum GymLeaderState {
    /** The building has no author-declared leader marker or trainer. */
    UNASSIGNED,
    /** Desired state; waiting only for safe preconditions such as a loaded chunk and a nearby player. */
    PENDING,
    /** A provider spawn was entered and its final side effect is intentionally not replayed after a restart/error. */
    VERIFYING,
    /** The exact RCT trainer at the exact saved home anchor is known by UUID. */
    ACTIVE,
    /** The provider result was ambiguous or invalid; an operator must repair or explicitly reset it. */
    DEGRADED;

    static GymLeaderState fromSaved(String value, boolean hasLeaderPosition, boolean hasLeaderUuid) {
        try {
            return GymLeaderState.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            // Worlds written before lifecycle tracking had only a UUID. Preserve
            // the strongest fact available and never infer that a spawn is safe.
            return hasLeaderUuid ? ACTIVE : hasLeaderPosition ? PENDING : UNASSIGNED;
        }
    }
}
