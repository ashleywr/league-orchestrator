package dev.leagueorchestrator;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.leagueorchestrator.spawn.RegionalSpawnFilter;
import dev.leagueorchestrator.world.LeagueCommands;
import dev.leagueorchestrator.world.WorldLeaguePlan;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
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

    @Override
    public void onInitialize() {
        ResourceManagerHelper.get(PackType.SERVER_DATA)
                .registerReloadListener(new LeagueDataReloadListener());
        ServerLifecycleEvents.SERVER_STARTED.register(WorldLeaguePlan::ensureAllOverworldPlans);
        CommandRegistrationCallback.EVENT.register(LeagueCommands::register);
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(RegionalSpawnFilter::filter);
    }
}
