package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.townhall.audit.AuditLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockItem.class)
abstract class AuditBlockItemMixin {
	@WrapMethod(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;")
	private InteractionResult townhall$auditPlace(BlockPlaceContext context, Operation<InteractionResult> original) {
		if (!(context.getPlayer() instanceof ServerPlayer player) || !AuditLog.enabled()) return original.call(context);
		try (var scope = AuditLog.playerScope(player, "block.place")) {
			InteractionResult result = original.call(context);
			if (result.consumesAction()) scope.commit();
			return result;
		}
	}
}
