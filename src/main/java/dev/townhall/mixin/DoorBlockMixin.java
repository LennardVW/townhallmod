package dev.townhall.mixin;

import dev.townhall.key.Keys;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locked doors (Keys): buttons, levers and other redstone can't open them (neighborChanged), and neither can mobs
 * such as villagers (setOpen, which players' own right-click doesn't use).
 */
@Mixin(DoorBlock.class)
abstract class DoorBlockMixin {

	@Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
	private void townhall$noRedstone(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston, CallbackInfo ci) {
		if (Keys.isLocked(level, pos, state)) ci.cancel();
	}

	@Inject(method = "setOpen", at = @At("HEAD"), cancellable = true)
	private void townhall$noMobs(Entity entity, Level level, BlockState state, BlockPos pos, boolean open, CallbackInfo ci) {
		if (Keys.isLocked(level, pos, state)) ci.cancel();
	}
}
