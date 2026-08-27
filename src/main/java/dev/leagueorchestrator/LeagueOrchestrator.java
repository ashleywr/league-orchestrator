package dev.leagueorchestrator;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.leagueorchestrator.spawn.RegionalSpawnFilter;
import dev.leagueorchestrator.world.LeagueCommands;
import dev.leagueorchestrator.world.LeagueWorldgen;
import dev.leagueorchestrator.world.AutoTownGymPlacement;
import dev.leagueorchestrator.world.RoadWeaverBridge;
import dev.leagueorchestrator.world.RoadWeaverPlacementAdapterBridge;
import dev.leagueorchestrator.world.RctGymLeaderBridge;
import dev.leagueorchestrator.world.SettlementPredictor;
import dev.leagueorchestrator.world.SettlementRealization;
import dev.leagueorchestrator.world.StarterTownBootstrap;
import dev.leagueorchestrator.world.TownStartPrewarmer;
import dev.leagueorchestrator.world.WorldLeaguePlan;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
        LeagueWorldgen.bootstrap();
        RoadWeaverPlacementAdapterBridge.install();
        if (FabricLoader.getInstance().isModLoaded("cobblemon-additions")) {
            ResourceManagerHelper.registerBuiltinResourcePack(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "kanto_classic"),
                    FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                    ResourcePackActivationType.NORMAL
            );
        } else {
            LOGGER.info("BCA pilot content is unavailable because cobblemon-additions is not installed; Frontier remains available.");
        }
        ResourceManagerHelper.get(PackType.SERVER_DATA)
                .registerReloadListener(new LeagueDataReloadListener());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
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
            SettlementPredictor.stop();
            TownStartPrewarmer.stop();
            StarterTownBootstrap.stop();
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
            AutoTownGymPlacement.tick(server);
            RctGymLeaderBridge.tick(server);
        });
        CommandRegistrationCallback.EVENT.register(LeagueCommands::register);
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(RegionalSpawnFilter::filter);
    }
}
