package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.townhall.dimension.DimensionSettings;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.TimeCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Loot tables and predicates with a "time_check" see the fixed time of a world with a "time" rule. */
@Mixin(TimeCheck.class)
abstract class TimeCheckMixin {

	@WrapOperation(method = "test(Lnet/minecraft/world/level/storage/loot/LootContext;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/clock/ServerClockManager$ServerClockInstance;totalTicks()J"))
	private long townhall$fixedTime(ServerClockManager.ServerClockInstance clock, Operation<Long> original, @Local(argsOnly = true) LootContext context) {
		long ticks = original.call(clock);
		Long fixed = DimensionSettings.fixedTime(context.getLevel().dimension());
		return fixed == null ? ticks : DimensionSettings.pin(ticks, fixed);
	}
}
