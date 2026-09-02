package com.sanhiruzu.leagueorchestrator.world;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.sanhiruzu.leagueorchestrator.data.GymArchetype;
import com.sanhiruzu.leagueorchestrator.data.LeagueDataReloadListener;
import com.sanhiruzu.leagueorchestrator.provider.ProviderRegistry;
import com.sanhiruzu.leagueorchestrator.provider.ProviderIntegrationCoordinator;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Minimal operator diagnostics while authored gym-site placement is added. */
public final class LeagueCommands {
    private LeagueCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context,
                                Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("league")
                // Travel is for players; everything else is operator tooling,
                // so the permission gate sits on the admin subtrees instead of
                // on the root.
                .then(Commands.literal("travel").executes(LeagueCommands::travel))
                .then(Commands.literal("info")
                        .requires(source -> source.hasPermission(2))
                        .executes(LeagueCommands::info))
                .then(Commands.literal("integration")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(LeagueCommands::integrationStatus)))
                .then(Commands.literal("trainers")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("reload").executes(LeagueCommands::reloadTrainers)))
                .then(Commands.literal("site")
                        .requires(source -> source.hasPermission(2))
                        .executes(LeagueCommands::site))
                .then(Commands.literal("predicted")
                        .requires(source -> source.hasPermission(2))
                        .executes(LeagueCommands::predicted))
                .then(Commands.literal("claim")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("pool", StringArgumentType.word())
                                .executes(LeagueCommands::claim)))
                .then(Commands.literal("town")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("nearest").executes(LeagueCommands::nearestTown))
                        .then(Commands.literal("register")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(LeagueCommands::registerTown)))
                        .then(Commands.literal("gym")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("pool", StringArgumentType.word())
                                                .executes(LeagueCommands::assignTownGym))))
                        .then(Commands.literal("leader")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.literal("status").executes(LeagueCommands::leaderStatus))
                                        .then(Commands.literal("reset").executes(LeagueCommands::resetLeader))))
                        .then(Commands.literal("place")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .then(Commands.argument("pool", StringArgumentType.word())
                                                .executes(LeagueCommands::placeTownGym))))));
    }

    private static int travel(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        if (!LeagueTravel.isAvailable(source.getServer())) {
            source.sendFailure(Component.literal(
                    "This world has no separate League dimension. The campaign is in the overworld."));
            return 0;
        }
        if (LeagueMount.isMounted(player.serverLevel())) {
            ServerLevel target = LeagueTravel.leave(player);
            source.sendSuccess(() -> Component.literal(
                    "Returned to " + target.dimension().location() + "."), false);
        } else {
            ServerLevel target = LeagueTravel.enter(player);
            source.sendSuccess(() -> Component.literal(
                    "Travelled to " + target.dimension().location()
                            + ". Use /league travel again to return."), false);
        }
        return 1;
    }

    /**
     * Lists the town chunks League predicts near the player, straight from the
     * active placement's pure seed maths.
     *
     * <p>The prediction depends only on the world seed, never on which
     * dimension is asking, so two worlds sharing a seed must print the same
     * list whether League is mounted in the overworld or in its own dimension.
     * Running this in each and diffing the output is the check that mounting
     * elsewhere did not move anything.
     */
    private static int predicted(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        int radius = 96;
        int centreX = player.chunkPosition().x;
        int centreZ = player.chunkPosition().z;
        long seed = source.getServer().getWorldData().worldGenOptions().seed();

        List<String> lines = source.getServer().registryAccess()
                .registryOrThrow(Registries.STRUCTURE_SET).entrySet().stream()
                .filter(entry -> entry.getValue().placement() instanceof LeagueTownStructurePlacement)
                .map(entry -> {
                    LeagueTownStructurePlacement placement =
                            (LeagueTownStructurePlacement) entry.getValue().placement();
                    String chunks = placement.potentialChunks(seed,
                                    centreX - radius, centreZ - radius, centreX + radius, centreZ + radius)
                            .stream()
                            .map(pos -> pos.x + "," + pos.z)
                            .collect(Collectors.joining(" "));
                    return entry.getKey().location() + " -> " + (chunks.isEmpty() ? "(none)" : chunks);
                })
                .toList();

        if (lines.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No League town placement is active. Enable a content pack that supplies one."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Seed " + seed + ", chunks within " + radius + " of " + centreX + "," + centreZ
                        + " (dimension " + player.level().dimension().location() + "):"), false);
        lines.forEach(line -> source.sendSuccess(() -> Component.literal("  " + line), false));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "League Orchestrator: " + LeagueDataReloadListener.current().allGyms().size()
                        + " gym archetypes, " + LeagueDataReloadListener.current().speciesPools().size() + " species pools, "
                        + LeagueDataReloadListener.current().townPalettes().size() + " town palettes, "
                        + LeagueDataReloadListener.current().routeProfiles().size() + " route profiles, "
                        + LeagueDataReloadListener.current().encounterProfiles().size() + " encounter profiles, and "
                        + LeagueDataReloadListener.current().legendaryEncounters().size() + " legendary definitions. "
                        + "Campaign: " + WorldLeaguePlan.get(context.getSource().getLevel())
                        .campaignIdFor(context.getSource().getLevel(), net.minecraft.core.BlockPos.containing(context.getSource().getPosition()))
                        .map(Object::toString).orElse("none") + "; RCT series: " + WorldLeaguePlan.get(context.getSource().getLevel())
                        .rctSeriesIdFor(context.getSource().getLevel(), net.minecraft.core.BlockPos.containing(context.getSource().getPosition()))
                        .orElse("none") + "; providers: "
                        + String.join(", ", ProviderRegistry.installedKnownProviders().keySet()) + "."), false);
        return 1;
    }

    private static int site(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        WorldGeography.Site atlas = WorldGeography.siteAt(player.serverLevel(), (int) player.getX(), (int) player.getZ());
        String profile = WorldGeography.profileAt(player.serverLevel(), player.getX(), player.getZ()).toString();
        String place = WorldLeaguePlan.get(player.serverLevel()).nearestTown(player.serverLevel(), player.blockPosition(), 256.0)
                .map(value -> " near " + value.name() + " [" + value.id() + "]")
                .orElse("");
        context.getSource().sendSuccess(() -> Component.literal(
                "Geography " + atlas.kind().name().toLowerCase() + " site " + atlas.cellX() + "," + atlas.cellZ()
                        + " uses profile " + profile + place + "."), false);
        return 1;
    }

    private static int integrationStatus(CommandContext<CommandSourceStack> context) {
        ProviderIntegrationCoordinator.Status status = ProviderIntegrationCoordinator.status();
        context.getSource().sendSuccess(() -> Component.literal("Provider integration: " + status.pending() + "/"
                + status.capacity() + " pending; " + status.openCircuits() + " operation circuits open; "
                + status.rejectedCapacity() + " capacity deferrals; " + status.completed() + " completed; "
                + status.degraded() + " degraded."), false);
        return 1;
    }

    /**
     * Picks up trainer progression files written into this world's own
     * datapacks since the last load (e.g. by scripts/generate_gym_progression.py)
     * without a full server restart. See RctGymLeaderBridge#reloadTrainerData.
     */
    private static int reloadTrainers(CommandContext<CommandSourceStack> context) {
        boolean reloaded = RctGymLeaderBridge.reloadTrainerData(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal(reloaded
                ? "Reloaded RCT trainer data."
                : "Could not reload RCT trainer data; check the server log."), true);
        return reloaded ? 1 : 0;
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
                .map(value -> "Nearest town: '" + value.name() + "' (" + value.id() + ") at " + value.center().toShortString()
                        + (value.bounds() == null ? " (planned or manual anchor)." : " (realized settlement zone)."))
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
        if (!GymStructurePlacement.place(player.serverLevel(), player.blockPosition(), player.getDirection(), gym.get())) {
            context.getSource().sendFailure(Component.literal("Could not place " + gym.get().structureTag()
                    + ". Confirm that its structure provider is installed and that the plot is clear."));
            return 0;
        }
        BlockPos leaderPosition = GymStructurePlacement.leaderPosition(player.blockPosition(), player.getDirection(), gym.get());
        plan.markGymPlaced(town.get(), player.blockPosition(), leaderPosition, !gym.get().trainerId().isBlank());
        RctGymLeaderBridge.enqueue(player.serverLevel(), town.get(), gym.get());
        context.getSource().sendSuccess(() -> Component.literal("Placed " + gym.get().id() + " for town '" + townId
                + "' at " + player.blockPosition().toShortString() + "."), true);
        return 1;
    }

    private static int leaderStatus(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String townId = StringArgumentType.getString(context, "id");
        Optional<TownAnchor> town = WorldLeaguePlan.get(player.serverLevel()).townAt(player.serverLevel(), player.blockPosition(), townId);
        if (town.isEmpty() || town.get().gymLeaderPosition() == null) {
            context.getSource().sendFailure(Component.literal("Town '" + townId + "' has no authored gym-leader anchor in this atlas site."));
            return 0;
        }
        TownAnchor anchor = town.get();
        String suffix = anchor.gymLeaderFailure().isBlank() ? "" : " Reason: " + anchor.gymLeaderFailure();
        context.getSource().sendSuccess(() -> Component.literal("Gym leader for '" + townId + "': " + anchor.gymLeaderState()
                + " at " + anchor.gymLeaderPosition().toShortString() + "; UUID: "
                + (anchor.gymLeaderUuid() == null ? "none" : anchor.gymLeaderUuid()) + "." + suffix), false);
        return 1;
    }

    private static int resetLeader(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String townId = StringArgumentType.getString(context, "id");
        WorldLeaguePlan plan = WorldLeaguePlan.get(player.serverLevel());
        Optional<TownAnchor> town = plan.townAt(player.serverLevel(), player.blockPosition(), townId);
        if (town.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No registered town named '" + townId + "' exists in this atlas site."));
            return 0;
        }
        Optional<TownAnchor> reset = plan.resetGymLeader(town.get());
        if (reset.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Only a DEGRADED gym leader with an authored anchor can be reset."));
            return 0;
        }
        ResourceLocation gymId = ResourceLocation.tryParse(plan.allocationAt(player.serverLevel(), reset.get().center(),
                "town:" + reset.get().id()).orElse(""));
        GymArchetype gym = gymId == null ? null : LeagueDataReloadListener.current().gymArchetypes().get(gymId);
        if (gym != null) RctGymLeaderBridge.enqueue(player.serverLevel(), reset.get(), gym);
        context.getSource().sendSuccess(() -> Component.literal("Reset leader state for '" + townId
                + "'. League will reconcile an exact RCT trainer before any new spawn."), true);
        return 1;
    }
}
