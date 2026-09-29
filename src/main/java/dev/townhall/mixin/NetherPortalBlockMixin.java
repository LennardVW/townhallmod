package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** World rule "mobs": false = nether portals don't spawn zombified piglins (randomTick does nothing else). */
@Mixin(NetherPortalBlock.class)
abstract class NetherPortalBlockMixin {

	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void townhall$noPiglins(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (!DimensionSettings.of(level.dimension()).mobs()) ci.cancel();
	}
}
