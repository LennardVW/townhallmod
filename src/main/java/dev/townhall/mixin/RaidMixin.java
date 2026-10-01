package dev.townhall.mixin;

import dev.townhall.dimension.DimensionSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World rule "mobs": false = a running raid in that world stops before its next wave, like vanilla does when the
 * "raids" game rule is turned off (Raids.tick drops stopped raids on the next tick).
 */
@Mixin(Raid.class)
abstract class RaidMixin {

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void townhall$stopRaid(ServerLevel level, CallbackInfo ci) {
		if (DimensionSettings.of(level.dimension()).mobs()) return;
		((Raid) (Object) this).stop();
		ci.cancel();
	}
}
