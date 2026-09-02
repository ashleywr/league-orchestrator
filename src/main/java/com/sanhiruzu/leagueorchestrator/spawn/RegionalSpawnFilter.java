package com.sanhiruzu.leagueorchestrator.spawn;

import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.sanhiruzu.leagueorchestrator.data.LeagueDataReloadListener;
import com.sanhiruzu.leagueorchestrator.world.LeagueMount;
import com.sanhiruzu.leagueorchestrator.world.WorldGeography;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Enforces the regional allow/deny pools defined in Region Engine profiles.
 * Directly created Pokémon have no Cobblemon spawn cause and are intentionally
 * left alone; this applies to normal wild-spawner output only.
 */
public final class RegionalSpawnFilter {
    private static final ResourceLocation KANTO_NATIVE_POOL = ResourceLocation.fromNamespaceAndPath(
            "league_orchestrator", "kanto_native");
    private static final ResourceLocation KANTO_STRUCTURE_ONLY_POOL = ResourceLocation.fromNamespaceAndPath(
            "league_orchestrator", "kanto_structure_only");

    private RegionalSpawnFilter() {}

    public static void filter(SpawnEvent<PokemonEntity> event) {
        PokemonEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)
                || !LeagueMount.isMounted(level)
                || entity.getSpawnCause() == null) {
            return;
        }

        ResourceLocation species = entity.getPokemon().getSpecies().getResourceIdentifier();
        // A mounted Kanto campaign owns its ordinary Gen-I wild encounters.
        // Keep them out of the social-hub overworld without touching command,
        // trainer, or otherwise directly created Pokémon.
        if (!LeagueMount.isMounted(level) && isKantoLeagueActive(level)) {
            if (LeagueDataReloadListener.current().poolContains(KANTO_NATIVE_POOL, species)) {
                event.cancel();
            }
            return;
        }

        if (WorldGeography.usesRegionEngine()) {
            // Region Engine-owned spawn policies are intentionally optional.
            // Keeping this filter passive here avoids a linkage requirement;
            // its API can later be bridged the same way as geography.
            return;
        }
        if (WorldGeography.profileAt(level, entity.getX(), entity.getZ()).equals(WorldGeography.KANTO_PROFILE)
                && !isAllowed(java.util.List.of(KANTO_NATIVE_POOL.toString()),
                java.util.List.of(KANTO_STRUCTURE_ONLY_POOL.toString()), species)) {
            event.cancel();
        }
    }

    private static boolean isKantoLeagueActive(ServerLevel sourceLevel) {
        ServerLevel leagueLevel = LeagueMount.level(sourceLevel.getServer());
        return LeagueMount.isMounted(leagueLevel)
                && WorldGeography.profileAt(leagueLevel, 0.0, 0.0).equals(WorldGeography.KANTO_PROFILE);
    }

    private static boolean isAllowed(java.util.List<String> allowPools, java.util.List<String> denyPools, ResourceLocation species) {
        for (String pool : denyPools) {
            if (contains(pool, species)) return false;
        }
        if (allowPools.isEmpty()) return true;
        boolean resolvedAllowPool = false;
        for (String pool : allowPools) {
            ResourceLocation poolId = poolId(pool);
            if (poolId == null || !LeagueDataReloadListener.current().hasSpeciesPool(poolId)) continue;
            resolvedAllowPool = true;
            if (LeagueDataReloadListener.current().poolContains(poolId, species)) return true;
        }
        // A profile may be published before its optional content datapack. Do
        // not turn that missing data into an accidental global spawn ban.
        return !resolvedAllowPool;
    }

    private static boolean contains(String rawPoolId, ResourceLocation species) {
        ResourceLocation pool = poolId(rawPoolId);
        return pool != null && LeagueDataReloadListener.current().poolContains(pool, species);
    }

    private static ResourceLocation poolId(String rawPoolId) {
        return ResourceLocation.tryParse(rawPoolId.startsWith("#") ? rawPoolId.substring(1) : rawPoolId);
    }
}
