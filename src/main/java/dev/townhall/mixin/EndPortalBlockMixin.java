package dev.townhall.mixin;

import dev.townhall.dimension.EndGate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Admin switch "end": while closed, end portal blocks outside the End send nobody to the End. */
@Mixin(EndPortalBlock.class)
abstract class EndPortalBlockMixin {

	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void townhall$endClosed(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier applier, boolean intersects, CallbackInfo ci) {
		if (level.isClientSide() || !EndGate.blocks(level)) return;
		EndGate.notifyClosed(entity);
		ci.cancel();
	}
}
