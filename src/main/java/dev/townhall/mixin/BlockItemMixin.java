package dev.townhall.mixin;

import dev.townhall.protection.Protection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block placement in worlds with "build": false (and before the rules are accepted). Doors/buttons still work. */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {

	@Inject(method = "place", at = @At("HEAD"), cancellable = true)
	private void townhall$buildProtection(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
		if (!(ctx.getPlayer() instanceof ServerPlayer player)) return;
		if (!Protection.mayChangeWorld(player, player.level(), ctx.getClickedPos())) {
			cir.setReturnValue(InteractionResult.FAIL);
			return;
		}
		// A bed occupies a second column: checking only its foot would let outsiders place its head in a claim.
		if (((BlockItem)(Object)this).getBlock() instanceof net.minecraft.world.level.block.AbstractBedBlock
				&& !Protection.mayChangeWorld(player, player.level(), ctx.getClickedPos().relative(ctx.getHorizontalDirection()))) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
