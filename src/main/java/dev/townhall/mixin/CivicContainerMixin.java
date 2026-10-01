package dev.townhall.mixin;

import dev.townhall.city.CivicSites;
import dev.townhall.city.PlotService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BaseContainerBlockEntity.class)
abstract class CivicContainerMixin {
	@Inject(method={"canOpen", "stillValid"},at=@At("HEAD"),cancellable=true)
	private void townhall$containerAccess(Player user,CallbackInfoReturnable<Boolean> cir) {
		BaseContainerBlockEntity container = (BaseContainerBlockEntity)(Object)this;
		if (user instanceof ServerPlayer player && container.getLevel() instanceof ServerLevel level
				&& (!PlotService.canUse(player,level,container.getBlockPos()) || !CivicSites.canUse(player,level,container.getBlockPos()))) cir.setReturnValue(false);
	}
}
