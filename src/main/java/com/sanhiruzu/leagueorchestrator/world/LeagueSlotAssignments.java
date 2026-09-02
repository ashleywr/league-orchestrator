package com.sanhiruzu.leagueorchestrator.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Which region profile, if any, each League dimension slot
 * ({@code 1..}{@link LeagueMount#SLOT_COUNT}) is currently assigned to.
 *
 * <p>Unlike {@link com.sanhiruzu.leagueorchestrator.config.LeagueConfig},
 * this is written by an operator command
 * ({@code /league admin slot <n> assign <region>}), not just read once at
 * startup - deliberately separate from LeagueConfig's "read-once, the world
 * is the single source of truth" contract, since a slot assignment is
 * exactly the thing that decides what the world's dimension registry will
 * contain next boot (see {@link LeagueDimensionRegistrar}). A change here
 * only takes effect after a server restart: dimension registries are not
 * {@code /reload}-able.</p>
 */
public final class LeagueSlotAssignments {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("league_orchestrator").resolve("league_slots.json");
    private static LeagueSlotAssignments current = new LeagueSlotAssignments();

    /** 1-based slot number (as a string, for stable Gson keys) to region profile id, e.g. "kanto". Absent = unassigned. */
    public Map<String, String> slots = new HashMap<>();

    public static LeagueSlotAssignments get() {
        return current;
    }

    public static void load() {
        try {
            if (Files.notExists(PATH)) {
                Files.createDirectories(PATH.getParent());
                current = new LeagueSlotAssignments();
                save();
                LeagueOrchestrator.LOGGER.info("Created League slot assignments at {}", PATH);
            } else {
                LeagueSlotAssignments loaded = GSON.fromJson(Files.readString(PATH), LeagueSlotAssignments.class);
                current = loaded != null ? loaded : new LeagueSlotAssignments();
            }
            if (current.slots == null) current.slots = new HashMap<>();
        } catch (IOException | RuntimeException exception) {
            LeagueOrchestrator.LOGGER.error("Could not load {}. No League dimension slots are assigned.", PATH, exception);
            current = new LeagueSlotAssignments();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(current));
        } catch (IOException exception) {
            LeagueOrchestrator.LOGGER.error("Could not save {}", PATH, exception);
        }
    }

    /** The region profile id assigned to a 1-based slot, or null if unassigned. */
    public String regionFor(int slot) {
        return slots.get(Integer.toString(slot));
    }

    /** Assigns a region profile to a 1-based slot and persists immediately. Takes effect after a restart. */
    public static void assign(int slot, String regionId) {
        current.slots.put(Integer.toString(slot), regionId);
        save();
    }

    /** Clears a slot's assignment and persists immediately. Takes effect after a restart. */
    public static void clear(int slot) {
        current.slots.remove(Integer.toString(slot));
        save();
    }
}
