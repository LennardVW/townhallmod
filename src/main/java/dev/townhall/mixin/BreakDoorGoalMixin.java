package dev.townhall.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.townhall.key.Keys;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zombies on hard break doors (BreakDoorGoal). Not locked ones: the goal doesn't start, or stops when the door gets locked. */
@Mixin(BreakDoorGoal.class)
abstract class BreakDoorGoalMixin extends DoorInteractGoal {

	private BreakDoorGoalMixin(Mob mob) {
		super(mob);
	}

	@ModifyReturnValue(method = {"canUse", "canContinueToUse"}, at = @At("RETURN"))
	private boolean townhall$notLockedDoors(boolean vanilla) {
		return vanilla && !(doorPos != null && Keys.isLocked(mob.level(), doorPos, mob.level().getBlockState(doorPos)));
	}
}
