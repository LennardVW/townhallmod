package dev.townhall.mixin;

import dev.townhall.key.Keys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Door locks follow the door block. DoorBlock doesn't override these two, so they run here; everything else only
 * pays an instanceof check. onPlace: a newly placed door drops any stale lock at its spot (covers doors removed
 * without a neighbor update, e.g. /setblock). affectNeighborsAfterRemoval: a removed door deletes its lock.
 */
@Mixin(BlockBehaviour.class)
abstract class BlockBehaviourMixin {

	@Inject(method = "onPlace", at = @At("HEAD"))
	private void townhall$newDoorHasNoLock(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
		if ((Object) this instanceof DoorBlock && level instanceof ServerLevel server) Keys.onDoorPlaced(server, pos, state, oldState);
	}

	@Inject(method = "affectNeighborsAfterRemoval", at = @At("HEAD"))
	private void townhall$removedDoorLosesLock(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston, CallbackInfo ci) {
		if ((Object) this instanceof DoorBlock) Keys.onDoorRemoved(level, pos, state);
	}
}
