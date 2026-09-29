package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.townhall.key.Keys;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Pistons pop doors (PushReaction.POPPED), even when isPushable says no. A locked door in the push or pop list
 * blocks the whole piston move instead, like obsidian.
 */
@Mixin(PistonStructureResolver.class)
abstract class PistonStructureResolverMixin {

	@Shadow
	@Final
	private Level level;

	@Shadow
	@Final
	private List<BlockPos> toPush;

	@Shadow
	@Final
	private List<BlockPos> toDestroy;

	@ModifyReturnValue(method = "resolve", at = @At("RETURN"))
	private boolean townhall$lockedDoorsBlock(boolean movable) {
		if (!movable) return false;
		for (BlockPos pos : toDestroy) if (Keys.isLocked(level, pos, level.getBlockState(pos))) return townhall$blocked();
		for (BlockPos pos : toPush) if (Keys.isLocked(level, pos, level.getBlockState(pos))) return townhall$blocked();
		return true;
	}

	private boolean townhall$blocked() {
		toPush.clear();
		toDestroy.clear();
		return false;
	}
}
