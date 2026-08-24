package dev.leagueorchestrator.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Loads simple, auditable JSON definitions without introducing an opaque config DSL. */
public final class LeagueDataReloadListener implements SimpleSynchronousResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static volatile LeagueData current = LeagueData.EMPTY;

    public static LeagueData current() {
        return current;
    }

    @Override
    public ResourceLocation getFabricId() {
        return ResourceLocation.fromNamespaceAndPath("league_orchestrator", "definitions");
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        Map<ResourceLocation, GymArchetype> gyms = new HashMap<>();
        Map<ResourceLocation, Set<ResourceLocation>> speciesPools = new HashMap<>();
        resourceManager.listResources("league_orchestrator/gym_archetypes", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseGym(fileId, resource, gyms));
        resourceManager.listResources("league_orchestrator/species_pools", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseSpeciesPool(fileId, resource, speciesPools));
        current = new LeagueData(Map.copyOf(gyms), Map.copyOf(speciesPools));
    }

    private static void parseGym(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                 Map<ResourceLocation, GymArchetype> gyms) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/gym_archetypes/"));
            Set<String> profiles = strings(root.getAsJsonArray("region_profiles"));
            gyms.put(id, new GymArchetype(
                    id,
                    requiredString(root, "pool"),
                    profiles,
                    boundedWeight(root),
                    optionalString(root, "structure_tag"),
                    optionalString(root, "trainer"),
                    optionalString(root, "badge")
            ));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid league gym archetype " + fileId, exception);
        }
    }

    private static void parseSpeciesPool(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                         Map<ResourceLocation, Set<ResourceLocation>> pools) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/species_pools/"));
            Set<ResourceLocation> species = new HashSet<>();
            for (String value : strings(root.getAsJsonArray("species"))) {
                species.add(ResourceLocation.parse(value));
            }
            pools.put(id, Set.copyOf(species));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid league species pool " + fileId, exception);
        }
    }

    private static ResourceLocation definitionId(ResourceLocation fileId, String prefix) {
        String path = fileId.getPath().substring(prefix.length(), fileId.getPath().length() - ".json".length());
        return ResourceLocation.fromNamespaceAndPath(fileId.getNamespace(), path);
    }

    private static ResourceLocation id(JsonObject object, String key, ResourceLocation fallback) {
        return object.has(key) ? ResourceLocation.parse(object.get(key).getAsString()) : fallback;
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing required field '" + key + "'");
        }
        return object.get(key).getAsString();
    }

    private static String optionalString(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    private static int boundedWeight(JsonObject object) {
        return Math.clamp(object.has("weight") ? object.get("weight").getAsInt() : 1, 1, 1000);
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> values = new HashSet<>();
        if (array != null) {
            for (JsonElement element : array) {
                values.add(element.getAsString());
            }
        }
        return Set.copyOf(values);
    }
}
