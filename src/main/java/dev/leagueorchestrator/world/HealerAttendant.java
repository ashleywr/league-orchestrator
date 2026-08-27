package dev.leagueorchestrator.world;

import com.cobblemon.mod.common.CobblemonVillagerProfessions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.phys.AABB;

/**
 * The runtime realization for the content-level {@code healer_attendant}
 * marker. The marker is deliberately semantic: a later content pack may
 * choose another model or a custom League NPC without requiring its
 * structures to be rebuilt.
 *
 * <p>The bundled default is Cobblemon's stationary nurse villager profession.
 * This class does not scan arbitrary BCA structures or guess a counter
 * position; it is called only by a future structure/module marker processor
 * once that processor has an author-declared safe standing position.</p>
 */
public final class HealerAttendant {
    public static final String ROLE_TAG = "league_orchestrator.healer_attendant";
    private HealerAttendant() {}

    /**
     * Ensures exactly one persistent nurse villager occupies the declared
     * marker. It never loads chunks and is safe to call again after a reload
     * or structure-reconciliation retry.
     */
    public static boolean ensureDefault(ServerLevel level, BlockPos marker) {
        AABB search = new AABB(marker).inflate(1.0D);
        if (!level.getEntitiesOfClass(Villager.class, search,
                entity -> entity.getTags().contains(ROLE_TAG)).isEmpty()) {
            return false;
        }

        Villager nurse = EntityType.VILLAGER.create(level);
        if (nurse == null) return false;
        nurse.setVillagerData(new VillagerData(VillagerType.PLAINS, CobblemonVillagerProfessions.NURSE, 5));
        nurse.moveTo(marker.getX() + 0.5D, marker.getY(), marker.getZ() + 0.5D, 0.0F, 0.0F);
        nurse.setCustomName(Component.literal("Pokémon Center Nurse"));
        nurse.setCustomNameVisible(false);
        nurse.setPersistenceRequired();
        nurse.setInvulnerable(true);
        nurse.setNoAi(true);
        nurse.addTag(ROLE_TAG);
        return level.addFreshEntity(nurse);
    }
}
