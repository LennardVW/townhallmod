package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/** World rule "mobs": false = trial spawners don't spawn (also not when a command overrides peaceful/spawn rules). */
@Mixin(TrialSpawner.class)
abstract class TrialSpawnerMixin {

	@Inject(method = "canSpawnInLevel", at = @At("HEAD"), cancellable = true)
	private void townhall$noMobs(ServerLevel level, CallbackInfoReturnable<Boolean> cir) {
		if (!DimensionSettings.of(level.dimension()).mobs()) cir.setReturnValue(false);
	}

	@Inject(method = "spawnMob", at = @At("HEAD"), cancellable = true)
	private void townhall$noSpawn(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Optional<UUID>> cir) {
		if (!DimensionSettings.of(level.dimension()).mobs()) cir.setReturnValue(Optional.empty());
	}
}
