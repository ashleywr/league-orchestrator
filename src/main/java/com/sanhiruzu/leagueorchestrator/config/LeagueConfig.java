package com.sanhiruzu.leagueorchestrator.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server policy for League Orchestrator: how a campaign is run, as opposed to
 * what a campaign contains.
 *
 * <p>Content packs own what exists - towns, gyms, trainers, routes, spawn
 * tables. This file owns the operator's choices about running them, so one
 * content pack can serve a solo world, a small co-op world, and a large server
 * without forking. A value belongs here only if an operator could reasonably
 * want it different and changing it cannot break a campaign's own pacing.
 *
 * <p>Deliberately absent: gym allocation ratios, town tiering, and encounter
 * bands. Those are campaign design, and letting a server tune them would
 * silently unbalance a pack the author tested. They stay in content packs.
 *
 * <p>Where League mounts is deliberately absent. That is decided by the world
 * type chosen at creation - a world containing the League dimension mounts
 * there - so the world itself is the single source of truth and no config
 * value can disagree with it.
 *
 * <p>Read once at startup, mirroring Region Engine's AtlasConfig.
 */
public final class LeagueConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("league-orchestrator.json");
    private static LeagueConfig current = defaults();

    /**
     * Per-tick nanosecond budget for optional provider work (RoadWeaver and
     * RCT calls). Exceeding it logs a slow-action warning; it does not cancel
     * work in flight. Raise it on a host with headroom, lower it if League
     * shows up in tick profiling.
     */
    public long serverActionBudgetNanos = 5_000_000L;

    /**
     * How many predicted towns a new world will try before giving up on moving
     * the shared spawn to a starter town. Each attempt loads chunks, so this
     * trades first-boot time against the odds of landing a good starter.
     */
    public int maxStartupTownCandidates = 3;

    /** How many predicted towns the first RoadWeaver endpoint search will consider. */
    public int maxStartupRouteCandidates = 12;

    /**
     * Block radius force-loaded around an external gym structure before
     * placement. Vanilla structure placement needs a loaded area; larger
     * values are safer for big structures and more expensive.
     */
    public int externalStructureLoadRadius = 128;

    /**
     * How close a player must be, in blocks, before a saved RCT gym leader is
     * reconciled into the world. Leaders never force chunk loading, so this
     * only decides how early an already-loaded anchor becomes eligible.
     */
    public double rctLeaderActivationDistance = 192.0;

    private static LeagueConfig defaults() {
        return new LeagueConfig();
    }

    public static LeagueConfig get() {
        return current;
    }

    public static void load() {
        try {
            if (Files.notExists(PATH)) {
                Files.createDirectories(PATH.getParent());
                current = defaults();
                Files.writeString(PATH, GSON.toJson(current));
                LeagueOrchestrator.LOGGER.info("Created League configuration at {}", PATH);
            } else {
                current = GSON.fromJson(Files.readString(PATH), LeagueConfig.class);
                if (current == null) current = defaults();
            }
            current.validate();
        } catch (IOException | RuntimeException exception) {
            LeagueOrchestrator.LOGGER.error("Could not load {}. Using safe League defaults.", PATH, exception);
            current = defaults();
        }
    }

    private void validate() {
        // One tick is 50ms. A budget at or above that measures nothing.
        serverActionBudgetNanos = Math.max(500_000L, Math.min(25_000_000L, serverActionBudgetNanos));
        maxStartupTownCandidates = Math.max(1, Math.min(32, maxStartupTownCandidates));
        maxStartupRouteCandidates = Math.max(1, Math.min(128, maxStartupRouteCandidates));
        externalStructureLoadRadius = Math.max(16, Math.min(512, externalStructureLoadRadius));
        rctLeaderActivationDistance = Math.max(16.0, Math.min(512.0, rctLeaderActivationDistance));
    }
}
