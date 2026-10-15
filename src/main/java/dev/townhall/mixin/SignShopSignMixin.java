package dev.townhall.mixin;

import dev.townhall.shop.sign.SignShopService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** After a player wrote on a sign: "[Laden]" signs on chests become sign shops. */
@Mixin(SignBlockEntity.class)
abstract class SignShopSignMixin {
    @Inject(method = "updateSignText", at = @At("TAIL"))
    private void townhall$signShop(Player player, SignTextSlot slot, List<FilteredText> lines, CallbackInfo ci) {
        if (slot == SignTextSlot.FRONT && player instanceof ServerPlayer sp) SignShopService.create(sp, (SignBlockEntity) (Object) this);
    }
}
