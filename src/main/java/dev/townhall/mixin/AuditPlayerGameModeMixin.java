package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.townhall.audit.AuditLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
abstract class AuditPlayerGameModeMixin {
	@Shadow @Final protected ServerPlayer player;

	@WrapMethod(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z")
	private boolean townhall$auditBreak(BlockPos position, Operation<Boolean> original) {
		if (!AuditLog.enabled()) return original.call(position);
		try (var scope = AuditLog.playerScope(player, "block.break")) {
			boolean success = original.call(position);
			if (success) scope.commit();
			return success;
		}
	}
}
