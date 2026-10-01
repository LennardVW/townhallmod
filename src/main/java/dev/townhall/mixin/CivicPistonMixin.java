package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.townhall.city.CivicSites;
import dev.townhall.city.PlotService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import java.util.List;

@Mixin(PistonStructureResolver.class)
abstract class CivicPistonMixin {
	@Shadow @Final private Level level;
	@Shadow @Final private BlockPos pistonPos;
	@Shadow @Final private Direction pushDirection;
	@Shadow @Final private List<BlockPos> toPush;
	@Shadow @Final private List<BlockPos> toDestroy;
	@ModifyReturnValue(method="resolve",at=@At("RETURN"))
	private boolean townhall$civicMove(boolean movable) {
		if (!movable || !(level instanceof ServerLevel server)) return movable;
		for (BlockPos pos:toDestroy) if (CivicSites.protectedAt(server,pos) || PlotService.pistonCrossesBoundary(server,pistonPos,pos)) return false;
		for (BlockPos pos:toPush) {
			BlockPos to = pos.relative(pushDirection);
			if (CivicSites.protectedAt(server,pos) || CivicSites.protectedAt(server,to)
					|| PlotService.pistonCrossesBoundary(server,pistonPos,pos) || PlotService.pistonCrossesBoundary(server,pistonPos,to)) return false;
		}
		return true;
	}
}
