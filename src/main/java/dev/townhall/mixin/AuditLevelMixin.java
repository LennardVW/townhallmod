package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.townhall.audit.AuditLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
abstract class AuditLevelMixin {
	@WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
	private boolean townhall$auditBlockChange(BlockPos position, BlockState state, int flags, int recursion, Operation<Boolean> original) {
		if (!((Object) this instanceof ServerLevel level) || !AuditLog.tracks(level)) return original.call(position, state, flags, recursion);
		BlockPos immutable = position.immutable();
		BlockState before = level.getBlockState(immutable);
		boolean success = original.call(position, state, flags, recursion);
		if (success) AuditLog.changed(level, immutable, before, level.getBlockState(immutable));
		return success;
	}
}
