package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.townhall.dimension.FixedClockManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;


/**
 * Sky light, mob spawning and other day/night attributes of a world are sampled from the clocks given here
 * (built once per world). Server worlds get a FixedClockManager so the "time" world rule can stop their clocks.
 */
@Mixin(EnvironmentAttributeSystem.class)
abstract class EnvironmentAttributeSystemMixin {

	@WrapOperation(method = "addDynamicLayers", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clockManager()Lnet/minecraft/world/clock/ClockManager;"))
	private static ClockManager townhall$fixedClocks(Level level, Operation<ClockManager> original) {
		ClockManager real = original.call(level);
		return level instanceof ServerLevel serverLevel ? new FixedClockManager(serverLevel, real) : real;
	}
}
