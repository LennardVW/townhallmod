package dev.townhall.mixin;

import dev.townhall.city.CivicSites;
import dev.townhall.city.PlotService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TransportItemsBetweenContainers.class)
abstract class CivicGolemMixin {
	@Inject(method="isContainerLocked",at=@At("RETURN"),cancellable=true)
	private void townhall$civicContainers(TransportItemsBetweenContainers.TransportItemTarget target,CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && target.blockEntity().getLevel() instanceof ServerLevel level
				&& (CivicSites.protectedAt(level,target.pos()) || PlotService.protects(level,target.pos()))) cir.setReturnValue(true);
	}
}
