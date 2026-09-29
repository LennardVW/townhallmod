package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.townhall.dimension.DimensionSettings;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * No sleeping in worlds with a fixed "time" rule or fixed thunder: the fixed night/thunder only exists there, but a
 * night skip would move the clock that every world shares. The bed answers like vanilla at daytime ("You can sleep only
 * at night or during thunderstorms"); setting the spawn point with the bed still works (it's checked before this).
 */
@Mixin(ServerPlayer.class)
abstract class BedSleepMixin {

	@WrapOperation(method = "startSleepInBed", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/attribute/BedRule;canSleep(Lnet/minecraft/world/level/Level;)Z"))
	private boolean townhall$noSleepInFixedWorlds(BedRule rule, Level level, Operation<Boolean> original) {
		return original.call(rule, level) && DimensionSettings.maySleep(level.dimension());
	}
}
