package com.sanhiruzu.leagueorchestrator.data;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sanhiruzu.leagueorchestrator.LeagueOrchestrator;
import com.sanhiruzu.leagueorchestrator.provider.ProviderRegistry;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
        Map<ResourceLocation, CampaignDefinition> campaigns = new HashMap<>();
        Map<ResourceLocation, TownPalette> townPalettes = new HashMap<>();
        Map<ResourceLocation, RouteProfile> routeProfiles = new HashMap<>();
        Map<ResourceLocation, EncounterProfile> encounterProfiles = new HashMap<>();
        Map<ResourceLocation, LegendaryEncounter> legendaryEncounters = new HashMap<>();
        Map<ResourceLocation, TrainerDefinition> trainers = new HashMap<>();
        Map<ResourceLocation, RewardDefinition> rewards = new HashMap<>();
        resourceManager.listResources("league_orchestrator/gym_archetypes", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseGym(fileId, resource, gyms));
        resourceManager.listResources("league_orchestrator/species_pools", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseSpeciesPool(fileId, resource, speciesPools));
        resourceManager.listResources("league_orchestrator/campaigns", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseCampaign(fileId, resource, campaigns));
        resourceManager.listResources("league_orchestrator/town_palettes", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseTownPalette(fileId, resource, townPalettes));
        resourceManager.listResources("league_orchestrator/route_profiles", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseRouteProfile(fileId, resource, routeProfiles));
        resourceManager.listResources("league_orchestrator/encounter_profiles", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseEncounterProfile(fileId, resource, encounterProfiles));
        resourceManager.listResources("league_orchestrator/legendary_encounters", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseLegendaryEncounter(fileId, resource, legendaryEncounters));
        resourceManager.listResources("league_orchestrator/trainers", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseTrainer(fileId, resource, trainers));
        resourceManager.listResources("league_orchestrator/rewards", path -> path.getPath().endsWith(".json"))
                .forEach((fileId, resource) -> parseReward(fileId, resource, rewards));

        campaigns.entrySet().removeIf(entry -> !validateCampaign(resourceManager, entry.getKey(), entry.getValue(), townPalettes, routeProfiles,
                encounterProfiles, legendaryEncounters, trainers, rewards));
        current = new LeagueData(
                Map.copyOf(gyms), Map.copyOf(speciesPools), Map.copyOf(campaigns),
                Map.copyOf(townPalettes), Map.copyOf(routeProfiles), Map.copyOf(encounterProfiles), Map.copyOf(legendaryEncounters),
                Map.copyOf(trainers), Map.copyOf(rewards));
        LeagueOrchestrator.LOGGER.info(
                "Loaded {} League campaigns with {} town palettes, {} route profiles, {} encounter profiles, and {} legendary definitions.",
                campaigns.size(), townPalettes.size(), routeProfiles.size(), encounterProfiles.size(), legendaryEncounters.size());
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
                    optionalString(root, "badge"),
                    resourceLocations(root.getAsJsonArray("rewards")),
                    optionalBlockPos(root.getAsJsonArray("leader_offset"))
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

    private static void parseCampaign(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                      Map<ResourceLocation, CampaignDefinition> campaigns) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/campaigns/"));
            Set<String> requirements = strings(root.getAsJsonArray("requires"));
            campaigns.put(id, new CampaignDefinition(
                    id,
                    CampaignMode.parse(optionalString(root, "mode", "fixed")),
                    optionalString(root, "seed_salt", id.toString()),
                    optionalString(root, "gym_pool"),
                    optionalString(root, "rct_series"),
                    strings(root.getAsJsonArray("town_names")).stream().sorted().toList(),
                    resourceLocationSet(root.getAsJsonArray("region_profiles")),
                    root.has("fallback") && root.get("fallback").getAsBoolean(),
                    Math.clamp(root.has("priority") ? root.get("priority").getAsInt() : 0, -1000, 1000),
                    resourceLocations(root.getAsJsonArray("town_palettes")),
                    resourceLocations(root.getAsJsonArray("route_profiles")),
                    resourceLocations(root.getAsJsonArray("encounter_profiles")),
                    resourceLocations(root.getAsJsonArray("legendary_encounters")),
                    resourceLocations(root.getAsJsonArray("trainers")),
                    resourceLocations(root.getAsJsonArray("rewards")),
                    requirements
            ));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid League campaign " + fileId, exception);
        }
    }

    private static void parseTownPalette(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                         Map<ResourceLocation, TownPalette> townPalettes) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/town_palettes/"));
            townPalettes.put(id, new TownPalette(id, strings(root.getAsJsonArray("region_profiles")),
                    requiredString(root, "generator"), resourceLocations(root.getAsJsonArray("structure_pools")),
                    strings(root.getAsJsonArray("port_types")), strings(root.getAsJsonArray("requires"))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid League town palette " + fileId, exception);
        }
    }

    private static void parseRouteProfile(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                          Map<ResourceLocation, RouteProfile> routeProfiles) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/route_profiles/"));
            int minimum = boundedLevel(root, "minimum_level", 1);
            int maximum = boundedLevel(root, "maximum_level", minimum);
            if (maximum < minimum) throw new IllegalArgumentException("maximum_level must be at least minimum_level");
            routeProfiles.put(id, new RouteProfile(id, strings(root.getAsJsonArray("region_profiles")),
                    requiredString(root, "renderer"), optionalString(root, "port_type", "road"), minimum, maximum,
                    requiredId(root, "encounter_profile"), strings(root.getAsJsonArray("requires"))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid League route profile " + fileId, exception);
        }
    }

    private static void parseEncounterProfile(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                              Map<ResourceLocation, EncounterProfile> encounterProfiles) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/encounter_profiles/"));
            int minimum = boundedLevel(root, "minimum_level", 1);
            int maximum = boundedLevel(root, "maximum_level", minimum);
            if (maximum < minimum) throw new IllegalArgumentException("maximum_level must be at least minimum_level");
            encounterProfiles.put(id, new EncounterProfile(id, requiredId(root, "species_pool"), minimum, maximum,
                    strings(root.getAsJsonArray("contexts")), strings(root.getAsJsonArray("requires"))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid League encounter profile " + fileId, exception);
        }
    }

    private static void parseLegendaryEncounter(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                                Map<ResourceLocation, LegendaryEncounter> legendaryEncounters) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/legendary_encounters/"));
            legendaryEncounters.put(id, new LegendaryEncounter(id, requiredId(root, "species"),
                    requiredString(root, "trigger"), strings(root.getAsJsonArray("region_profiles")),
                    strings(root.getAsJsonArray("requires"))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid League legendary encounter " + fileId, exception);
        }
    }

    private static void parseTrainer(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                     Map<ResourceLocation, TrainerDefinition> trainers) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/trainers/"));
            int minimum = boundedLevel(root, "minimum_level", 1);
            int maximum = boundedLevel(root, "maximum_level", minimum);
            if (maximum < minimum) throw new IllegalArgumentException("maximum_level must be at least minimum_level");
            trainers.put(id, new TrainerDefinition(id, requiredString(root, "provider"),
                    resourceLocations(root.getAsJsonArray("team_pools")), minimum, maximum,
                    strings(root.getAsJsonArray("requires"))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid League trainer definition " + fileId, exception);
        }
    }

    private static void parseReward(ResourceLocation fileId, net.minecraft.server.packs.resources.Resource resource,
                                    Map<ResourceLocation, RewardDefinition> rewards) {
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            ResourceLocation id = id(root, "id", definitionId(fileId, "league_orchestrator/rewards/"));
            rewards.put(id, new RewardDefinition(id, requiredString(root, "provider"), requiredString(root, "kind"),
                    requiredString(root, "value"), Math.clamp(root.has("amount") ? root.get("amount").getAsInt() : 1, 1, 64),
                    strings(root.getAsJsonArray("requires"))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid League reward definition " + fileId, exception);
        }
    }

    private static boolean validateCampaign(ResourceManager resourceManager, ResourceLocation id, CampaignDefinition campaign,
                                            Map<ResourceLocation, TownPalette> towns,
                                            Map<ResourceLocation, RouteProfile> routes,
                                            Map<ResourceLocation, EncounterProfile> encounters,
                                            Map<ResourceLocation, LegendaryEncounter> legendaries,
                                            Map<ResourceLocation, TrainerDefinition> trainers,
                                            Map<ResourceLocation, RewardDefinition> rewards) {
        Set<String> missingProviders = new HashSet<>(ProviderRegistry.missing(campaign.requiredProviders()));
        boolean validRctSeries = true;
        if (!campaign.fallback()) {
            if (campaign.rctSeriesId().isBlank()) {
                LeagueOrchestrator.LOGGER.warn("Campaign {} is unavailable because it does not declare an RCT series.", id);
                validRctSeries = false;
            } else if (!campaign.rctSeriesId().matches("[a-z0-9_./-]+")) {
                LeagueOrchestrator.LOGGER.warn("Campaign {} declares invalid RCT series ID '{}'.", id, campaign.rctSeriesId());
                validRctSeries = false;
            } else if (resourceManager.getResource(ResourceLocation.fromNamespaceAndPath("rctmod", "series/" + campaign.rctSeriesId() + ".json")).isEmpty()) {
                LeagueOrchestrator.LOGGER.warn("Campaign {} declares RCT series '{}' but does not ship data/rctmod/series/{}.json.",
                        id, campaign.rctSeriesId(), campaign.rctSeriesId());
                validRctSeries = false;
            }
            missingProviders.addAll(ProviderRegistry.missing(Set.of("rctmod", "rctapi")));
        }
        boolean valid = validateReferences(id, "town palette", campaign.townPalettes(), towns)
                & validateReferences(id, "route profile", campaign.routeProfiles(), routes)
                & validateReferences(id, "encounter profile", campaign.encounterProfiles(), encounters)
                & validateReferences(id, "legendary encounter", campaign.legendaryEncounters(), legendaries)
                & validateReferences(id, "trainer", campaign.trainers(), trainers)
                & validateReferences(id, "reward", campaign.rewards(), rewards);
        for (ResourceLocation townId : campaign.townPalettes()) {
            TownPalette definition = towns.get(townId);
            if (definition != null) missingProviders.addAll(ProviderRegistry.missing(definition.requiredProviders()));
        }
        for (ResourceLocation routeId : campaign.routeProfiles()) {
            RouteProfile definition = routes.get(routeId);
            if (definition != null) missingProviders.addAll(ProviderRegistry.missing(definition.requiredProviders()));
        }
        for (ResourceLocation encounterId : campaign.encounterProfiles()) {
            EncounterProfile definition = encounters.get(encounterId);
            if (definition != null) missingProviders.addAll(ProviderRegistry.missing(definition.requiredProviders()));
        }
        for (ResourceLocation legendaryId : campaign.legendaryEncounters()) {
            LegendaryEncounter definition = legendaries.get(legendaryId);
            if (definition != null) missingProviders.addAll(ProviderRegistry.missing(definition.requiredProviders()));
        }
        for (ResourceLocation trainerId : campaign.trainers()) {
            TrainerDefinition definition = trainers.get(trainerId);
            if (definition != null) missingProviders.addAll(ProviderRegistry.missing(definition.requiredProviders()));
        }
        for (ResourceLocation rewardId : campaign.rewards()) {
            RewardDefinition definition = rewards.get(rewardId);
            if (definition != null) missingProviders.addAll(ProviderRegistry.missing(definition.requiredProviders()));
        }
        if (!missingProviders.isEmpty()) {
            LeagueOrchestrator.LOGGER.warn("Campaign {} is unavailable because provider mods are missing: {}.", id, String.join(", ", missingProviders));
            valid = false;
        }
        return valid && validRctSeries;
    }

    private static <T> boolean validateReferences(ResourceLocation campaign, String label,
                                                  List<ResourceLocation> references, Map<ResourceLocation, T> definitions) {
        boolean valid = true;
        for (ResourceLocation reference : references) {
            if (!definitions.containsKey(reference)) {
                LeagueOrchestrator.LOGGER.warn("Campaign {} references missing {} {}.", campaign, label, reference);
                valid = false;
            }
        }
        return valid;
    }

    private static ResourceLocation definitionId(ResourceLocation fileId, String prefix) {
        String path = fileId.getPath().substring(prefix.length(), fileId.getPath().length() - ".json".length());
        return ResourceLocation.fromNamespaceAndPath(fileId.getNamespace(), path);
    }

    private static ResourceLocation id(JsonObject object, String key, ResourceLocation fallback) {
        return object.has(key) ? ResourceLocation.parse(object.get(key).getAsString()) : fallback;
    }

    private static ResourceLocation requiredId(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing required resource ID '" + key + "'");
        }
        return ResourceLocation.parse(object.get(key).getAsString());
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

    private static String optionalString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static int boundedWeight(JsonObject object) {
        return Math.clamp(object.has("weight") ? object.get("weight").getAsInt() : 1, 1, 1000);
    }

    private static int boundedLevel(JsonObject object, String key, int fallback) {
        return Math.clamp(object.has(key) ? object.get(key).getAsInt() : fallback, 1, 100);
    }

    private static List<ResourceLocation> resourceLocations(JsonArray array) {
        return strings(array).stream().map(ResourceLocation::parse).sorted().toList();
    }

    private static Set<ResourceLocation> resourceLocationSet(JsonArray array) {
        return resourceLocations(array).stream().collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static net.minecraft.core.BlockPos optionalBlockPos(JsonArray array) {
        if (array == null) return null;
        if (array.size() != 3) throw new IllegalArgumentException("leader_offset must contain exactly three integers");
        return new net.minecraft.core.BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
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
