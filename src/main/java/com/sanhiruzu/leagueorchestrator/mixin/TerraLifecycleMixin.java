package com.sanhiruzu.leagueorchestrator.mixin;

import com.sanhiruzu.leagueorchestrator.world.TerraLeaguePreset;
import net.minecraft.core.WritableRegistry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional hook: Terra is allowed to remain absent in non-Kanto installations. */
@Pseudo
@Mixin(targets = "com.dfsek.terra.lifecycle.util.LifecycleUtil", remap = false)
public abstract class TerraLifecycleMixin {
    @Inject(method = "initialize", at = @At("TAIL"), remap = false, require = 0)
    private static void league_orchestrator$registerKantoDimension(
            WritableRegistry<Biome> biomes,
            WritableRegistry<WorldPreset> worldPresets,
            CallbackInfo ci
    ) {
        TerraLeaguePreset.register(worldPresets);
    }
}
