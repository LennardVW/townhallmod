package dev.townhall.mixin;

import dev.townhall.city.CivicSites;
import dev.townhall.city.PlotService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
abstract class CivicFluidMixin {
	@Inject(method="spreadTo",at=@At("HEAD"),cancellable=true)
	private void townhall$fluidBoundary(LevelAccessor level,BlockPos pos,BlockState state,Direction direction,FluidState fluid,CallbackInfo ci) {
		if (level instanceof ServerLevel server && (CivicSites.protectedAt(server,pos) || PlotService.pistonCrossesBoundary(server,pos.relative(direction.getOpposite()),pos))) ci.cancel();
	}
}
