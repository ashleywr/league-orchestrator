package dev.leagueorchestrator.spawn;

import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.leagueorchestrator.data.LeagueDataReloadListener;
import dev.regionengine.policy.RegionPolicyService;
import dev.regionengine.policy.RegionProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Enforces the regional allow/deny pools defined in Region Engine profiles.
 * Directly created Pokémon have no Cobblemon spawn cause and are intentionally
 * left alone; this applies to normal wild-spawner output only.
 */
public final class RegionalSpawnFilter {
    private RegionalSpawnFilter() {}

    public static void filter(SpawnEvent<PokemonEntity> event) {
        PokemonEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)
                || !level.dimension().equals(Level.OVERWORLD)
                || entity.getSpawnCause() == null) {
            return;
        }

        RegionProfile profile = RegionPolicyService.profileAt(level, entity.getX(), entity.getZ());
        ResourceLocation species = entity.getPokemon().getSpecies().getResourceIdentifier();
        if (!isAllowed(profile.cobblemon(), species)) {
            event.cancel();
        }
    }

    private static boolean isAllowed(RegionProfile.CobblemonRules rules, ResourceLocation species) {
        for (String pool : rules.denySpeciesTags()) {
            if (contains(pool, species)) return false;
        }
        if (rules.allowSpeciesTags().isEmpty()) return true;
        boolean resolvedAllowPool = false;
        for (String pool : rules.allowSpeciesTags()) {
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
