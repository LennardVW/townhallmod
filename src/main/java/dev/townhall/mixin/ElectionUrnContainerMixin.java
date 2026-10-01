package dev.townhall.mixin;

import dev.townhall.election.ElectionService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Defence in depth: no vanilla menu, including helper/operator menus or other mod menu requests. */
@Mixin(BaseContainerBlockEntity.class)
public abstract class ElectionUrnContainerMixin {
    @Inject(method = "canOpen", at = @At("HEAD"), cancellable = true)
    private void townhall$electionUrn(Player player, CallbackInfoReturnable<Boolean> cir) {
        BaseContainerBlockEntity container = (BaseContainerBlockEntity) (Object) this;
        if (container.getLevel() instanceof ServerLevel level && ElectionService.protectedAt(level, container.getBlockPos()))
            cir.setReturnValue(false);
    }
}
