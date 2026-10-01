package dev.townhall.mixin;

import dev.townhall.city.CivicSites;
import dev.townhall.city.PlotService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.util.List;

@Mixin(ServerExplosion.class)
abstract class CivicExplosionMixin {
	@Shadow @Final private ServerLevel level;
	@ModifyVariable(method="interactWithBlocks",at=@At("HEAD"),argsOnly=true)
	private List<BlockPos> townhall$civicBlocks(List<BlockPos> blocks) {
		return blocks.stream().filter(p -> !CivicSites.protectedAt(level,p) && !PlotService.protects(level,p)).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
	}
	@ModifyVariable(method="createFire",at=@At("HEAD"),argsOnly=true)
	private List<BlockPos> townhall$civicFire(List<BlockPos> blocks) {
		return blocks.stream().filter(p -> !CivicSites.protectedAt(level,p) && !PlotService.protects(level,p)).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
	}
}
