package com.sanhiruzu.leagueorchestrator;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.sanhiruzu.leagueorchestrator.config.LeagueConfig;
import com.sanhiruzu.leagueorchestrator.data.LeagueDataReloadListener;
import com.sanhiruzu.leagueorchestrator.spawn.RegionalSpawnFilter;
import com.sanhiruzu.leagueorchestrator.world.LeagueCommands;
import com.sanhiruzu.leagueorchestrator.world.LeagueDimensionRegistrar;
import com.sanhiruzu.leagueorchestrator.world.LeagueMount;
import com.sanhiruzu.leagueorchestrator.world.LeagueSlotAssignments;
import com.sanhiruzu.leagueorchestrator.world.LeagueWorldgen;
import com.sanhiruzu.leagueorchestrator.world.AutoTownGymPlacement;
import com.sanhiruzu.leagueorchestrator.world.RoadWeaverBridge;
import com.sanhiruzu.leagueorchestrator.world.RoadsideStopFallback;
import com.sanhiruzu.leagueorchestrator.world.RoadWeaverPlacementAdapterBridge;
import com.sanhiruzu.leagueorchestrator.world.RctGymLeaderBridge;
import com.sanhiruzu.leagueorchestrator.world.SettlementPredictor;
import com.sanhiruzu.leagueorchestrator.world.SettlementRealization;
import com.sanhiruzu.leagueorchestrator.world.StarterTownBootstrap;
import com.sanhiruzu.leagueorchestrator.world.TownStartPrewarmer;
import com.sanhiruzu.leagueorchestrator.world.WorldLeaguePlan;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Cobblemon-facing campaign layer. Region Engine owns geography; this mod
 * owns the saved league plan, site allocations, and regional species policy.
 */
public final class LeagueOrchestrator implements ModInitializer {
    public static final String MOD_ID = "league_orchestrator";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static int deferredContentReconcileTicks;

    @Override
    public void onInitialize() {
        LeagueConfig.load();
        LeagueSlotAssignments.load();
        LeagueDimensionRegistrar.register();
        LeagueWorldgen.bootstrap();
        RoadWeaverPlacementAdapterBridge.install();
        // Shared across every dimension slot regardless of which region is
        // assigned, so it is registered unconditionally rather than gated
        // behind cobblemon-additions or a specific region's content pack.
        ResourceManagerHelper.registerBuiltinResourcePack(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "league_slots"),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                ResourcePackActivationType.ALWAYS_ENABLED
        );
        if (FabricLoader.getInstance().isModLoaded("cobblemon-additions")) {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "kanto_classic"),
                    FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                    ResourcePackActivationType.ALWAYS_ENABLED
            );
            ResourceManagerHelper.registerBuiltinResourcePack(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "alola_dimension"),
                    FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                    ResourcePackActivationType.ALWAYS_ENABLED
            );
            if (FabricLoader.getInstance().isModLoaded("lithostitched")
                    && FabricLoader.getInstance().isModLoaded("regions_unexplored")) {
                // This is an optional data-only compatibility layer.  It
                // enriches the specifically named Terra mountain-cavern
                // biome with Regions Unexplored's maintained placed features
                // without making either worldgen mod mandatory for League.
                ResourceManagerHelper.registerBuiltinResourcePack(
                        ResourceLocation.fromNamespaceAndPath(MOD_ID, "kanto_ru_caverns"),
                        FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                        ResourcePackActivationType.ALWAYS_ENABLED
                );
            }
        } else {
            LOGGER.info("BCA pilot content is unavailable because cobblemon-additions is not installed; Frontier remains available.");
        }
        if (FabricLoader.getInstance().isModLoaded("terra")) {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "kanto_dimension"),
                    FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                    ResourcePackActivationType.DEFAULT_ENABLED
            );
        }
        ResourceManagerHelper.get(PackType.SERVER_DATA)
                .registerReloadListener(new LeagueDataReloadListener());
        // Resolve before any level loads, so classification below is correct.
        ServerLifecycleEvents.SERVER_STARTING.register(LeagueMount::resolve);
        ServerWorldEvents.LOAD.register((server, level) -> LeagueMount.classify(level));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LeagueMount.verifyAtlasScope(server);
            WorldLeaguePlan.ensureMountedPlan(server);
            WorldLeaguePlan.reconcileRegionPlaces(server);
            WorldLeaguePlan.reconcileRoadWeaverCampaign(server);
            WorldLeaguePlan.reconcileRctGymLeaders(server);
            StarterTownBootstrap.begin(server);
            if (!RoadWeaverPlacementAdapterBridge.isInstalled()) {
                TownStartPrewarmer.reconcile(server);
            }
            SettlementPredictor.planSpawnSite(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LeagueMount.clear();
            SettlementPredictor.stop();
            TownStartPrewarmer.stop();
            StarterTownBootstrap.stop();
            RoadsideStopFallback.stop();
            RoadWeaverBridge.stop();
            RctGymLeaderBridge.stop();
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            SettlementRealization.realize(level, chunk);
            WorldLeaguePlan.reconcileRctGymLeadersInChunk(level, chunk);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++deferredContentReconcileTicks % 20 == 0) {
                WorldLeaguePlan.reconcileDeferredCampaignContent(server);
            }
            SettlementPredictor.planPlayerSites(server);
            TownStartPrewarmer.tick(server);
            StarterTownBootstrap.tick(server);
            RoadsideStopFallback.tick(server);
            AutoTownGymPlacement.tick(server);
            RctGymLeaderBridge.tick(server);
        });
        CommandRegistrationCallback.EVENT.register(LeagueCommands::register);
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(RegionalSpawnFilter::filter);
    }
}
