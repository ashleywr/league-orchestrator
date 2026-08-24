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
                                .executes(LeagueCommands::claim)))
                .then(Commands.literal("town")
                        .then(Commands.literal("nearest").executes(LeagueCommands::nearestTown))
                        .then(Commands.literal("register")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(LeagueCommands::registerTown)))
                        .then(Commands.literal("gym")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("pool", StringArgumentType.word())
                                                .executes(LeagueCommands::assignTownGym))))
                        .then(Commands.literal("place")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("pool", StringArgumentType.word())
                                                .executes(LeagueCommands::placeTownGym))))));
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

    private static int registerTown(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String townId = StringArgumentType.getString(context, "id");
        Optional<TownAnchor> town = WorldLeaguePlan.get(player.serverLevel())
                .registerTown(player.serverLevel(), player.blockPosition(), townId);
        context.getSource().sendSuccess(() -> Component.literal(town
                .map(value -> "Registered town '" + value.id() + "' at " + value.center().toShortString()
                        + " in atlas site " + value.siteX() + "," + value.siteZ() + ".")
                .orElse("Towns must be registered on continental land, not ocean or an island chain.")), true);
        return town.isPresent() ? 1 : 0;
    }

    private static int nearestTown(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Optional<TownAnchor> town = WorldLeaguePlan.get(player.serverLevel())
                .nearestTown(player.serverLevel(), player.blockPosition(), 384.0);
        context.getSource().sendSuccess(() -> Component.literal(town
                .map(value -> "Nearest town: '" + value.id() + "' at " + value.center().toShortString()
                        + (value.bounds() == null ? " (manual anchor)." : " (BCA zone registered)."))
                .orElse("No League town is registered within 384 blocks in this atlas site.")), false);
        return town.isPresent() ? 1 : 0;
    }

    private static int assignTownGym(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String townId = StringArgumentType.getString(context, "id");
        String pool = StringArgumentType.getString(context, "pool");
        WorldLeaguePlan plan = WorldLeaguePlan.get(player.serverLevel());
        Optional<TownAnchor> town = plan.townAt(player.serverLevel(), player.blockPosition(), townId);
        Optional<GymArchetype> gym = town.flatMap(value -> plan.gymForTown(player.serverLevel(), value, pool));
        context.getSource().sendSuccess(() -> Component.literal(gym
                .map(value -> "Assigned " + value.id() + " to town '" + townId + "'.")
                .orElse("No registered town or no unused gym from pool " + pool + " is valid here.")), true);
        return gym.isPresent() ? 1 : 0;
    }

    private static int placeTownGym(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String townId = StringArgumentType.getString(context, "id");
        String pool = StringArgumentType.getString(context, "pool");
        WorldLeaguePlan plan = WorldLeaguePlan.get(player.serverLevel());
        Optional<TownAnchor> town = plan.townAt(player.serverLevel(), player.blockPosition(), townId);
        if (town.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No registered town named '" + townId + "' exists in this atlas site."));
            return 0;
        }
        if (town.get().gymPosition() != null) {
            context.getSource().sendFailure(Component.literal("Town '" + townId + "' already has a placed gym at "
                    + town.get().gymPosition().toShortString() + "."));
            return 0;
        }
        if (town.get().center().distSqr(player.blockPosition()) > 256.0 * 256.0) {
            context.getSource().sendFailure(Component.literal("Stand within 256 blocks of the town anchor to place its gym."));
            return 0;
        }

        Optional<GymArchetype> gym = plan.gymForTown(player.serverLevel(), town.get(), pool);
        if (gym.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No unused gym from pool " + pool + " is valid for this town."));
            return 0;
        }
        if (!GymStructurePlacement.place(player.serverLevel(), player.blockPosition(), gym.get())) {
            context.getSource().sendFailure(Component.literal("Could not place " + gym.get().structureTag()
                    + ". Confirm that its structure provider is installed and that the plot is clear."));
            return 0;
        }
        plan.markGymPlaced(town.get(), player.blockPosition());
        context.getSource().sendSuccess(() -> Component.literal("Placed " + gym.get().id() + " for town '" + townId
                + "' at " + player.blockPosition().toShortString() + "."), true);
        return 1;
    }
}
