package dev.townhall.mixin;

import dev.townhall.key.DoorLocks;
import dev.townhall.key.Keys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Explosions skip locked doors (both halves). The support block may still blow up: the door keeps standing
 * (DoorBlockMixin). Separate from ServerExplosionMixin (world rule "explosions"); both ModifyVariables chain.
 */
@Mixin(ServerExplosion.class)
abstract class LockedDoorExplosionMixin {

	@Shadow
	@Final
	private ServerLevel level;

	@ModifyVariable(method = "interactWithBlocks", at = @At("HEAD"), argsOnly = true)
	private List<BlockPos> townhall$skipLockedDoors(List<BlockPos> blocks) {
		if (blocks.isEmpty() || DoorLocks.get(level.getServer()).size() == 0) return blocks;
		List<BlockPos> kept = new ArrayList<>(blocks.size());
		for (BlockPos pos : blocks) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof DoorBlock) || !Keys.isLocked(level, pos, state)) kept.add(pos);
		}
		return kept.size() == blocks.size() ? blocks : kept;
	}
}
