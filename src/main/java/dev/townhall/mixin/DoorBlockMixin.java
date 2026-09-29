package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.townhall.key.Keys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locked doors (Keys): buttons, levers and other redstone can't open them (neighborChanged), and neither can mobs
 * such as villagers (setOpen, which players' own right-click doesn't use). A locked door also keeps standing when the
 * block under it disappears (broken, pushed away by a piston, blown up, burnt, taken by an enderman ...), otherwise
 * removing the support would pop the door and get around the lock.
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

	/** Lower half lost its support (vanilla returns air): a locked door stays. The check only runs in that case. */
	@ModifyReturnValue(method = "updateShape", at = @At("RETURN"))
	private BlockState townhall$keepLockedDoor(BlockState result, BlockState state, LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks,
			BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, net.minecraft.util.RandomSource random) {
		if (direction != Direction.DOWN || result.getBlock() instanceof DoorBlock || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) return result;
		return level instanceof Level l && Keys.isLocked(l, pos, state) ? state : result;
	}
}
