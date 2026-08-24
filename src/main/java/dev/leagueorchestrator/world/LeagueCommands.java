package dev.leagueorchestrator.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.leagueorchestrator.data.GymArchetype;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.regionengine.policy.RegionPolicyService;
import dev.regionengine.world.atlas.AtlasRegion;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/** Minimal operator diagnostics while authored gym-site placement is added. */
public final class LeagueCommands {
    private LeagueCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
                                Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("league")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("info").executes(LeagueCommands::info))
                .then(Commands.literal("site").executes(LeagueCommands::site))
                .then(Commands.literal("claim")
                        .then(Commands.argument("pool", StringArgumentType.word())
                                .executes(LeagueCommands::claim))));
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "League Orchestrator: " + LeagueDataReloadListener.current().allGyms().size()
                        + " gym archetypes, " + LeagueDataReloadListener.current().speciesPools().size() + " species pools."), false);
        return 1;
    }

    private static int site(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        AtlasRegion atlas = RegionPolicyService.atlasRegionAt(player.serverLevel().getSeed(), player.getX(), player.getZ());
        String profile = RegionPolicyService.profileAt(player.serverLevel(), player.getX(), player.getZ()).id().toString();
        context.getSource().sendSuccess(() -> Component.literal(
                "Atlas " + atlas.kind().name().toLowerCase() + " site " + atlas.cellX() + "," + atlas.cellZ()
                        + " uses profile " + profile + "."), false);
        return 1;
    }

    private static int claim(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String pool = StringArgumentType.getString(context, "pool");
        Optional<GymArchetype> gym = WorldLeaguePlan.get(player.serverLevel())
                .allocate(player.serverLevel(), player.blockPosition(), "primary", pool);
        context.getSource().sendSuccess(() -> Component.literal(gym
                .map(value -> "Allocated " + value.id() + " from " + pool + " at this atlas site.")
                .orElse("No unclaimed archetype in pool " + pool + " is valid for this site.")), true);
        return gym.isPresent() ? 1 : 0;
    }
}
