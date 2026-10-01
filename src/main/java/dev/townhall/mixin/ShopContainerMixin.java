package dev.townhall.mixin;

import dev.townhall.shop.ShopService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Deny opening through every vanilla path and invalidate a menu opened before registration. */
@Mixin(BaseContainerBlockEntity.class)
abstract class ShopContainerMixin {
    @Inject(method = {"canOpen", "stillValid"}, at = @At("HEAD"), cancellable = true)
    private void townhall$shopMarker(Player player, CallbackInfoReturnable<Boolean> cir) {
        BlockEntity be = (BlockEntity) (Object) this;
        if (be.getLevel() instanceof ServerLevel level && ShopService.protectedAt(level, be.getBlockPos())) cir.setReturnValue(false);
    }
}
