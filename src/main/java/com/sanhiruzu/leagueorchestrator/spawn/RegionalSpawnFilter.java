package com.sanhiruzu.leagueorchestrator.spawn;

import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.sanhiruzu.leagueorchestrator.data.CampaignDefinition;
import com.sanhiruzu.leagueorchestrator.data.LeagueDataReloadListener;
import com.sanhiruzu.leagueorchestrator.world.LeagueMount;
import com.sanhiruzu.leagueorchestrator.world.WorldGeography;
import com.sanhiruzu.leagueorchestrator.world.WorldLeaguePlan;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Enforces the wild-spawn allow/deny pools declared by whichever campaign is
 * mounted in a League dimension slot at the entity's position, and keeps
 * every region-claimed species out of every level that is not its own -
 * including the hub overworld, which has no mounted campaign of its own to
 * check against. Directly created Pokémon have no Cobblemon spawn cause and
 * are intentionally left alone; this applies to normal wild-spawner output
 * only.
 */
public final class RegionalSpawnFilter {
    private RegionalSpawnFilter() {}

    public static void filter(SpawnEvent<PokemonEntity> event) {
        PokemonEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || entity.getSpawnCause() == null) {
            return;
        }

        if (WorldGeography.usesRegionEngine()) {
            // Region Engine-owned spawn policies are intentionally optional.
            // Keeping this filter passive here avoids a linkage requirement;
            // its API can later be bridged the same way as geography.
            return;
        }

        ResourceLocation species = entity.getPokemon().getSpecies().getResourceIdentifier();

        if (!LeagueMount.isMounted(level)) {
            // A region's own biome-tag bridges (e.g. Kanto's is_arid override
            // pointing at mountains) are Cobblemon category definitions, not
            // dimension-scoped ones - the same biome registry entries are
            // shared with the hub by design. Without this check, a species
            // that only became spawnable through such a bridge would also
            // wild-spawn in the hub wherever that biome occurs. A species no
            // campaign has claimed is unaffected and spawns normally here.
            if (LeagueDataReloadListener.current().isRegionClaimed(species)) {
                event.cancel();
            }
            return;
        }

        Optional<CampaignDefinition> campaign = WorldLeaguePlan.get(level).campaignFor(level, entity.blockPosition());
        if (campaign.isEmpty()) return;

        if (!isAllowed(campaign.get(), species)) {
            event.cancel();
        }
    }

    private static boolean isAllowed(CampaignDefinition campaign, ResourceLocation species) {
        ResourceLocation structureOnly = campaign.structureOnlySpeciesPool();
        if (structureOnly != null && LeagueDataReloadListener.current().poolContains(structureOnly, species)) {
            return false;
        }
        ResourceLocation wild = campaign.wildSpeciesPool();
        if (wild == null) return true;
        // A campaign may be published before its optional species-pool
        // datapack. Do not turn that missing content into an accidental
        // global spawn ban.
        if (!LeagueDataReloadListener.current().hasSpeciesPool(wild)) return true;
        return LeagueDataReloadListener.current().poolContains(wild, species);
    }
}
