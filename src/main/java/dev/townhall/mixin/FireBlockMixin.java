package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** World rule "fire": false = fire goes out at its first tick, so it never spreads or burns blocks away. */
@Mixin(FireBlock.class)
abstract class FireBlockMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void townhall$noFire(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (!DimensionSettings.of(level.dimension()).fire()) {
			level.removeBlock(pos, false);
			ci.cancel();
		}
	}
}
