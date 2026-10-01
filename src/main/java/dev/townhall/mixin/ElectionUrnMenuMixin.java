package dev.townhall.mixin;

import dev.townhall.election.ElectionService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Also invalidate a vanilla menu created before the empty barrel was assigned to an election. */
@Mixin(ChestMenu.class)
public abstract class ElectionUrnMenuMixin {
    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void townhall$electionMenu(Player player, CallbackInfoReturnable<Boolean> cir) {
        ChestMenu menu = (ChestMenu) (Object) this;
        if (menu.getContainer() instanceof BarrelBlockEntity barrel
                && barrel.getLevel() instanceof ServerLevel level
                && ElectionService.protectedAt(level, barrel.getBlockPos())) cir.setReturnValue(false);
    }
}
