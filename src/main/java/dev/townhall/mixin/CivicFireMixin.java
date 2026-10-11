package dev.townhall.mixin;

import dev.townhall.city.CivicSites;
import dev.townhall.city.PlotService;
import dev.townhall.key.DoorLocks;
import dev.townhall.key.Keys;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
abstract class CivicFireMixin {
	@Inject(method="checkBurnOut",at=@At("HEAD"),cancellable=true)
	private void townhall$protectBurning(Level level,BlockPos pos,int chance,RandomSource random,int age,CallbackInfo ci) {
		if (level instanceof ServerLevel server && (CivicSites.protectedAt(server,pos) || PlotService.protects(server,pos))) ci.cancel();
		else if (level instanceof ServerLevel server && DoorLocks.get(server.getServer()).size() > 0 && Keys.isLocked(server,pos,server.getBlockState(pos))) ci.cancel();
	}
}
