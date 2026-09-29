package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** World rule "mobs": false = monster spawners don't spawn. */
@Mixin(BaseSpawner.class)
abstract class BaseSpawnerMixin {

	@Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
	private void townhall$noMobs(ServerLevel level, BlockPos pos, CallbackInfo ci) {
		if (!DimensionSettings.of(level.dimension()).mobs()) ci.cancel();
	}
}
